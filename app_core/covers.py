from pathlib import Path
import re, requests

def fetch_cover(code,description,cache_dir):
    code=re.sub(r'\D','',str(code or '')); cache=Path(cache_dir); cache.mkdir(parents=True,exist_ok=True)
    out=cache/f'{code or abs(hash(description))}.jpg'
    if out.exists() and out.stat().st_size>1500: return str(out)
    urls=[]
    if len(code) in (10,13):
        try:
            j=requests.get('https://www.googleapis.com/books/v1/volumes',params={'q':f'isbn:{code}','maxResults':5},timeout=7).json()
            for it in j.get('items',[]):
                u=((it.get('volumeInfo') or {}).get('imageLinks') or {}).get('thumbnail')
                if u: urls.append(u.replace('http://','https://').replace('&zoom=1','&zoom=2'))
        except Exception: pass
        urls += [f'https://covers.openlibrary.org/b/isbn/{code}-L.jpg?default=false']
    for u in urls:
        try:
            r=requests.get(u,timeout=8,headers={'User-Agent':'Mozilla/5.0'}); ct=r.headers.get('content-type','')
            if r.ok and 'image' in ct and len(r.content)>2000:
                out.write_bytes(r.content); return str(out)
        except Exception: pass
    return ''
