from __future__ import annotations
import os, webbrowser
from pathlib import Path
from datetime import datetime
from kivy.app import App
from kivy.clock import Clock
from kivy.core.audio import SoundLoader
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.spinner import Spinner
from kivy.uix.scrollview import ScrollView
from kivy.uix.gridlayout import GridLayout
from kivy.uix.popup import Popup
from kivy.uix.image import AsyncImage
from kivy.utils import platform
try:
    from plyer import filechooser
except Exception:
    filechooser=None
from app_core.database import Database
from app_core.importers import import_excel,import_locations_excel,import_negative_excel,normalize_tracking
from app_core.exporter import export_missing
from app_core.covers import fetch_cover

class CPOApp(App):
    title='Conferente de Pedidos Online'
    def build(self):
        self.user_data=Path(self.user_data_dir); self.user_data.mkdir(parents=True,exist_ok=True)
        self.db=Database(self.user_data/'dados.db'); self.batch_id=None; self.mode='PRODUTOS'; self.history=[]
        self.s_ok=SoundLoader.load('assets/ok.wav'); self.s_err=SoundLoader.load('assets/error.wav'); self.s_win=SoundLoader.load('assets/victory.wav')
        root=BoxLayout(orientation='vertical',padding=dp(8),spacing=dp(6))
        top=BoxLayout(size_hint_y=None,height=dp(46),spacing=dp(5))
        self.batch=Spinner(text='Selecione uma lista',values=(),size_hint_x=.38); self.batch.bind(text=self.on_batch)
        top.add_widget(self.batch)
        for txt,fn in [('Importar',self.pick_report),('Locais',self.pick_locations),('Negativos',self.pick_negatives),('Exportar',self.export_excel)]:
            b=Button(text=txt); b.bind(on_release=fn); top.add_widget(b)
        root.add_widget(top)
        modes=BoxLayout(size_hint_y=None,height=dp(44),spacing=dp(6))
        self.bp=Button(text='✓ Produtos'); self.bk=Button(text='📦 Pacotes'); self.bp.bind(on_release=lambda *_:self.set_mode('PRODUTOS')); self.bk.bind(on_release=lambda *_:self.set_mode('PACOTES'))
        modes.add_widget(self.bp); modes.add_widget(self.bk); root.add_widget(modes)
        self.summary=Label(text='Nenhuma lista selecionada',size_hint_y=None,height=dp(60)); root.add_widget(self.summary)
        scanrow=BoxLayout(size_hint_y=None,height=dp(56),spacing=dp(6))
        self.scan=TextInput(hint_text='Bipe ISBN / EAN / SKU',multiline=False,font_size='20sp'); self.scan.bind(on_text_validate=self.do_scan)
        scanrow.add_widget(self.scan); b=Button(text='BIPAR',size_hint_x=.25); b.bind(on_release=self.do_scan); scanrow.add_widget(b); root.add_widget(scanrow)
        fr=BoxLayout(size_hint_y=None,height=dp(44),spacing=dp(5))
        self.filter=Spinner(text='TODOS',values=['TODOS','PENDENTES','PARCIAIS','FALTANDO','CONFERIDOS','NEGATIVOS'],size_hint_x=.35); self.filter.bind(text=lambda *_:self.refresh())
        self.search=TextInput(hint_text='Pesquisar',multiline=False); self.search.bind(text=lambda *_:self.refresh()); fr.add_widget(self.filter); fr.add_widget(self.search); root.add_widget(fr)
        body=BoxLayout(spacing=dp(6))
        sv=ScrollView(size_hint_x=.62); self.list=GridLayout(cols=1,size_hint_y=None,spacing=dp(4)); self.list.bind(minimum_height=self.list.setter('height')); sv.add_widget(self.list); body.add_widget(sv)
        self.details=Label(text='Selecione um item',halign='left',valign='top'); self.details.bind(size=lambda w,s:setattr(w,'text_size',s)); body.add_widget(self.details); root.add_widget(body)
        self.hist=Label(text='Últimos bipes: —',size_hint_y=None,height=dp(44)); root.add_widget(self.hist)
        self.reload_batches(); Clock.schedule_once(lambda *_:setattr(self.scan,'focus',True),.4); return root
    def alert(self,title,msg,victory=False):
        if victory and self.s_win: self.s_win.play()
        p=Popup(title=title,content=Label(text=msg,halign='center'),size_hint=(.9,.42)); p.open()
    def choose(self,cb,ext='*.xlsx'):
        if not filechooser: self.alert('Arquivo','Seletor de arquivos indisponível.'); return
        filechooser.open_file(on_selection=lambda x: cb(x[0]) if x else None,filters=[ext])
    def pick_report(self,*_): self.choose(self.import_report)
    def pick_locations(self,*_): self.choose(self.import_locations)
    def pick_negatives(self,*_): self.choose(self.import_negatives)
    def import_report(self,path):
        try:
            rows=import_excel(path); name=Path(path).stem; bid=self.db.create_batch(name,Path(path).name); n=self.db.insert_items(bid,rows); self.reload_batches(); self.batch.text=f'{bid} - {name}'; self.alert('Importação concluída',f'{n} itens importados.\nRastreamentos encontrados: {sum(bool(r.get("tracking_normalized")) for r in rows)}')
        except Exception as e: self.alert('Erro ao importar',str(e))
    def import_locations(self,path):
        try: n=self.db.import_locations(import_locations_excel(path)); self.refresh(); self.alert('Localizações',f'{n} códigos atualizados na base permanente.')
        except Exception as e: self.alert('Erro',str(e))
    def import_negatives(self,path):
        if not self.batch_id: return self.alert('Atenção','Selecione uma lista primeiro.')
        try: n=self.db.apply_negatives(self.batch_id,import_negative_excel(path)); self.filter.text='NEGATIVOS'; self.refresh(); self.alert('Negativos',f'{n} itens sinalizados.')
        except Exception as e: self.alert('Erro',str(e))
    def reload_batches(self):
        vals=[f"{r['id']} - {r['name']}" for r in self.db.batches()]; self.batch.values=vals
        if vals and self.batch.text=='Selecione uma lista': self.batch.text=vals[0]
    def on_batch(self,_,text):
        try: self.batch_id=int(text.split(' - ',1)[0]); self.refresh()
        except: pass
    def set_mode(self,m):
        self.mode=m; self.scan.hint_text='Bipe ISBN / EAN / SKU' if m=='PRODUTOS' else 'Bipe o rastreamento do pacote'; self.scan.text=''; self.refresh(); self.scan.focus=True
    def do_scan(self,*_):
        if not self.batch_id: return self.alert('Atenção','Selecione uma lista.')
        raw=self.scan.text.strip(); self.scan.text=''; self.scan.focus=True
        if not raw: return
        if self.mode=='PRODUTOS': status,row=self.db.scan_product(self.batch_id,raw)
        else: status,row=self.db.scan_package(self.batch_id,normalize_tracking(raw))
        if status=='OK':
            if self.s_ok: self.s_ok.play(); self.add_hist('✓ '+raw); self.show_item(row); self.refresh()
            if self.mode=='PACOTES':
                s=self.db.package_summary(self.batch_id)
                if s['total'] and s['pending']==0: self.alert('🎉 Conferência concluída',f'Parabéns!\nTodos os {s["total"]} pacotes desta conferência foram embalados com sucesso.\nNenhum pacote pendente.',True)
        elif status=='ALREADY_DONE':
            if self.s_err:self.s_err.play(); self.add_hist('⚠ já conferido: '+raw); self.alert('Já conferido','Este código já foi totalmente conferido.')
        else:
            if self.s_err:self.s_err.play(); self.add_hist('✖ não encontrado: '+raw); self.alert('Não encontrado',f'Código não localizado:\n{raw}')
    def add_hist(self,t): self.history=[t]+self.history[:3]; self.hist.text='Últimos bipes:  '+'   |   '.join(self.history)
    def refresh(self):
        if not self.batch_id:return
        if self.mode=='PRODUTOS':
            s=self.db.summary(self.batch_id); self.summary.text=f"PRODUTOS   Esperado: {s['expected']}   Bipado: {s['checked']}   Faltando: {s['missing']}"
        else:
            s=self.db.package_summary(self.batch_id); self.summary.text=f"PACOTES   Total: {s['total']}   Embalados: {s['done']}   Pendentes: {s['pending']}"
        self.list.clear_widgets(); rows=self.db.items(self.batch_id,self.filter.text,self.search.text.strip())
        for r in rows[:500]:
            miss=max(r['quantity_expected']-r['quantity_checked'],0); pkg='✓' if r['package_status']=='EMBALADO' else '○'
            txt=f"{r['barcode']}  |  {r['description'][:55]}\nQtd {r['quantity_checked']}/{r['quantity_expected']}  Faltam {miss}  SS: {r['location_simpleset'] or '-'}  📦{pkg}"
            b=Button(text=txt,size_hint_y=None,height=dp(64),halign='left'); b.bind(on_release=lambda _b,rr=r:self.show_item(rr)); self.list.add_widget(b)
    def show_item(self,r):
        self.details.text=(f"DESCRIÇÃO\n{r['description']}\n\nCódigo: {r['barcode']}\nPedido: {r['order_id']}\nQtd: {r['quantity_checked']}/{r['quantity_expected']}\nLocalização SS: {r['location_simpleset'] or '-'}\nNegativo: {r['negative_quantity']}\n\nRastreamento:\n{r['tracking_original'] or '-'}\nPacote: {r['package_status']}")
    def export_excel(self,*_):
        if not self.batch_id:return self.alert('Atenção','Selecione uma lista.')
        try:
            out=self.user_data/f'faltando_{datetime.now():%Y%m%d_%H%M}.xlsx'; export_missing(self.db.items(self.batch_id,'TODOS',''),out); self.alert('Exportado',f'Arquivo salvo em:\n{out}')
        except Exception as e:self.alert('Erro',str(e))

if __name__=='__main__': CPOApp().run()
