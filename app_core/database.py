from __future__ import annotations
import sqlite3
from pathlib import Path

SCHEMA='''
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS batches(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,source_file TEXT,imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS items(
 id INTEGER PRIMARY KEY AUTOINCREMENT,batch_id INTEGER NOT NULL,order_id TEXT,buyer_name TEXT,buyer_document TEXT,platform TEXT,sku TEXT,barcode TEXT,
 description TEXT,quantity_expected INTEGER NOT NULL DEFAULT 1,quantity_checked INTEGER NOT NULL DEFAULT 0,unit_price REAL DEFAULT 0,
 location_nerus TEXT DEFAULT '',location_simpleset TEXT DEFAULT '',source TEXT,status TEXT NOT NULL DEFAULT 'PENDENTE',
 negative_quantity INTEGER NOT NULL DEFAULT 0,negative_product TEXT DEFAULT '',negative_grade TEXT DEFAULT '',negative_last_purchase TEXT DEFAULT '',negative_profit_center TEXT DEFAULT '',
 tracking_original TEXT DEFAULT '',tracking_normalized TEXT DEFAULT '',package_status TEXT NOT NULL DEFAULT 'PENDENTE',package_checked_at TEXT DEFAULT '',
 FOREIGN KEY(batch_id) REFERENCES batches(id) ON DELETE CASCADE);
CREATE INDEX IF NOT EXISTS idx_items_barcode ON items(barcode);
CREATE INDEX IF NOT EXISTS idx_items_batch ON items(batch_id);
CREATE INDEX IF NOT EXISTS idx_items_tracking ON items(tracking_normalized);
CREATE TABLE IF NOT EXISTS location_catalog(code TEXT PRIMARY KEY,location_simpleset TEXT NOT NULL DEFAULT '',updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
'''
class Database:
    def __init__(self,path):
        self.path=Path(path); self.path.parent.mkdir(parents=True,exist_ok=True)
        self.conn=sqlite3.connect(self.path); self.conn.row_factory=sqlite3.Row; self.conn.executescript(SCHEMA)
    def create_batch(self,name,source):
        c=self.conn.execute('INSERT INTO batches(name,source_file) VALUES(?,?)',(name,source)); self.conn.commit(); return c.lastrowid
    def batches(self): return self.conn.execute('SELECT * FROM batches ORDER BY id DESC').fetchall()
    def delete_batch(self,bid): self.conn.execute('DELETE FROM batches WHERE id=?',(bid,)); self.conn.commit()
    def rename_batch(self,bid,name): self.conn.execute('UPDATE batches SET name=? WHERE id=?',(name,bid)); self.conn.commit()
    def insert_items(self,bid,rows):
        sql='''INSERT INTO items(batch_id,order_id,buyer_name,buyer_document,platform,sku,barcode,description,quantity_expected,unit_price,location_nerus,location_simpleset,source,status,tracking_original,tracking_normalized,package_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDENTE',?,?,'PENDENTE')'''
        p=[]
        for r in rows:
            loc=r.get('location_simpleset','')
            cat=self.conn.execute('SELECT location_simpleset FROM location_catalog WHERE code=?',(r.get('barcode',''),)).fetchone()
            if cat: loc=cat[0]
            p.append((bid,r.get('order_id',''),r.get('buyer_name',''),r.get('buyer_document',''),r.get('platform',''),r.get('sku',''),r.get('barcode',''),r.get('description',''),int(r.get('quantity_expected') or 1),float(r.get('unit_price') or 0),r.get('location_nerus',''),loc,r.get('source',''),r.get('tracking_original',''),r.get('tracking_normalized','')))
        self.conn.executemany(sql,p)
        # reuse previous product checks by order+code
        self.conn.execute('''UPDATE items AS n SET quantity_checked=MIN(n.quantity_expected,COALESCE((SELECT MAX(o.quantity_checked) FROM items o WHERE o.batch_id<>n.batch_id AND o.order_id=n.order_id AND (o.barcode=n.barcode OR o.sku=n.sku)),0)) WHERE n.batch_id=?''',(bid,))
        self.conn.execute("UPDATE items SET status=CASE WHEN quantity_checked>=quantity_expected THEN 'CONFERIDO' WHEN quantity_checked>0 THEN 'PARCIAL' ELSE 'PENDENTE' END WHERE batch_id=?",(bid,))
        self.conn.commit(); return len(p)
    def items(self,bid,flt='TODOS',search=''):
        q='SELECT * FROM items WHERE batch_id=?'; a=[bid]
        if flt=='PENDENTES': q+=" AND status='PENDENTE'"
        elif flt=='PARCIAIS': q+=" AND status='PARCIAL'"
        elif flt=='CONFERIDOS': q+=" AND status='CONFERIDO'"
        elif flt=='FALTANDO': q+=' AND quantity_checked<quantity_expected'
        elif flt=='NEGATIVOS': q+=' AND negative_quantity<0'
        if search:
            q+=' AND (barcode LIKE ? OR sku LIKE ? OR description LIKE ? OR order_id LIKE ? OR tracking_original LIKE ?)'; s=f'%{search}%'; a += [s]*5
        q+=' ORDER BY description COLLATE NOCASE'
        return self.conn.execute(q,a).fetchall()
    def summary(self,bid):
        r=self.conn.execute('''SELECT COUNT(*) lines,COALESCE(SUM(quantity_expected),0) expected,COALESCE(SUM(quantity_checked),0) checked,COALESCE(SUM(MAX(quantity_expected-quantity_checked,0)),0) missing FROM items WHERE batch_id=?''',(bid,)).fetchone(); return dict(r)
    def scan_product(self,bid,code):
        code=str(code).strip()
        rows=self.conn.execute('SELECT * FROM items WHERE batch_id=? AND (barcode=? OR sku=?) ORDER BY CASE WHEN quantity_checked<quantity_expected THEN 0 ELSE 1 END,id',(bid,code,code)).fetchall()
        if not rows: return 'NOT_FOUND',None
        target=next((r for r in rows if r['quantity_checked']<r['quantity_expected']),None)
        if not target: return 'ALREADY_DONE',rows[0]
        new=target['quantity_checked']+1; status='CONFERIDO' if new>=target['quantity_expected'] else 'PARCIAL'
        self.conn.execute('UPDATE items SET quantity_checked=?,status=? WHERE id=?',(new,status,target['id'])); self.conn.commit()
        return 'OK',self.conn.execute('SELECT * FROM items WHERE id=?',(target['id'],)).fetchone()
    def import_locations(self,rows):
        n=0
        for r in rows:
            c=str(r['barcode']); l=str(r['location_simpleset']);
            self.conn.execute('''INSERT INTO location_catalog(code,location_simpleset,updated_at) VALUES(?,?,CURRENT_TIMESTAMP) ON CONFLICT(code) DO UPDATE SET location_simpleset=excluded.location_simpleset,updated_at=CURRENT_TIMESTAMP''',(c,l))
            self.conn.execute('UPDATE items SET location_simpleset=? WHERE barcode=? OR sku=?',(l,c,c)); n+=1
        self.conn.commit(); return n
    def apply_negatives(self,bid,rows):
        n=0
        for r in rows:
            c=r['barcode']; cur=self.conn.execute('''UPDATE items SET negative_quantity=?,negative_product=?,negative_grade=?,negative_last_purchase=?,negative_profit_center=? WHERE batch_id=? AND (barcode=? OR sku=?)''',(r['negative_quantity'],r.get('product',''),r.get('grade',''),r.get('last_purchase',''),r.get('profit_center',''),bid,c,c)); n+=cur.rowcount
        self.conn.commit(); return n
    def package_summary(self,bid):
        # distinct normalized tracking = one package, even if multiple items/order lines
        r=self.conn.execute('''SELECT COUNT(*) total,SUM(CASE WHEN package_status='EMBALADO' THEN 1 ELSE 0 END) done FROM (SELECT tracking_normalized,CASE WHEN MIN(package_status)='EMBALADO' THEN 'EMBALADO' ELSE 'PENDENTE' END package_status FROM items WHERE batch_id=? AND TRIM(tracking_normalized)<>'' GROUP BY tracking_normalized)''',(bid,)).fetchone()
        total=int(r['total'] or 0); done=int(r['done'] or 0); return {'total':total,'done':done,'pending':max(total-done,0)}
    def scan_package(self,bid,tracking_norm):
        rows=self.conn.execute("SELECT * FROM items WHERE batch_id=? AND tracking_normalized=?",(bid,tracking_norm)).fetchall()
        if not rows: return 'NOT_FOUND',None
        if all(r['package_status']=='EMBALADO' for r in rows): return 'ALREADY_DONE',rows[0]
        self.conn.execute("UPDATE items SET package_status='EMBALADO',package_checked_at=CURRENT_TIMESTAMP WHERE batch_id=? AND tracking_normalized=?",(bid,tracking_norm)); self.conn.commit()
        return 'OK',self.conn.execute('SELECT * FROM items WHERE batch_id=? AND tracking_normalized=? LIMIT 1',(bid,tracking_norm)).fetchone()
