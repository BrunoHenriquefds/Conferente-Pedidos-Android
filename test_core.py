from pathlib import Path
import tempfile
from app_core.importers import import_excel, normalize_tracking
from app_core.database import Database

src=Path('/mnt/data/mercado livre 3007 con(1).xlsx')
if src.exists():
    rows=import_excel(src)
    assert rows and rows[0]['tracking_normalized']=='47640886859', rows[0]
    assert normalize_tracking('MEL47640886859')=='47640886859'
    assert normalize_tracking('47640886859FMXDF01')=='47640886859'
    with tempfile.TemporaryDirectory() as td:
        db=Database(Path(td)/'x.db'); bid=db.create_batch('teste','x.xlsx'); db.insert_items(bid,rows)
        s=db.package_summary(bid); assert s['total']>0
        st,_=db.scan_package(bid,'47640886859'); assert st=='OK'
        st,_=db.scan_package(bid,'47640886859'); assert st=='ALREADY_DONE'
        code=rows[0]['barcode']; st,_=db.scan_product(bid,code); assert st in ('OK','ALREADY_DONE')
        print('OK',len(rows),s)
else:
    print('arquivo de teste não encontrado')
