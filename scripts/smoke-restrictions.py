"""Exercise the restriction engine against every OKS target; these short probes are not routes."""
import argparse,json,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base',default='http://127.0.0.1:8081');p.add_argument('--dataset',required=True);p.add_argument('--smoke',required=True);p.add_argument('--output',required=True);a=p.parse_args()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def api(path,body=None):
    req=urllib.request.Request(a.base+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    with opener.open(req,timeout=60) as response:return json.load(response)
reference=json.loads(Path(a.smoke).read_text(encoding='utf-8-sig'));imp=reference['import']['id']
rules=api('/api/v1/restrictions/rules');assert len(rules['rulesAtDn200'])==11;assert len(rules['dimensions'])==18
source=json.loads(Path(a.dataset).read_text(encoding='utf-8-sig'))
checks=[]
for ordinal,feature in enumerate(source['features']):
    if feature['properties']['object_type']!='oks_connection_point':continue
    x,y=feature['geometry']['coordinates'][:2]
    request={'coordinates':[[x+.0001,y],[x,y]],'srid':4326,'diameter':200,'targetOrdinal':ordinal}
    result=api('/api/v1/imports/'+imp+'/restrictions/check-segment',request)
    assert result['status'] in ('ALLOWED','BLOCKED','INDETERMINATE')
    assert result['srid']==32637 and result['lengthM']>0
    assert abs(sum(s['lengthM'] for s in result['sections'])-result['lengthM'])<1e-6
    checks.append({'targetOrdinal':ordinal,'targetId':feature['properties']['id'],'request':request,'result':result})
result={'status':'PASS','importId':imp,'probeCount':len(checks),'note':'Fixed short diagnostic probes, not routing results','decisions':{k:sum(c['result']['status']==k for c in checks) for k in ('ALLOWED','BLOCKED','INDETERMINATE')},'checks':checks}
Path(a.output).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k!='checks'},ensure_ascii=False,indent=2))
