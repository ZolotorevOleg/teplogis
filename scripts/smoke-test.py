"""Smoke check against a running service using the supplied dataset and independent baseline."""
import argparse, json, hashlib, urllib.request
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--base',default='http://127.0.0.1:8080')
p.add_argument('--dataset',required=True)
p.add_argument('--reference',required=True)
p.add_argument('--output',required=True)
a=p.parse_args()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(path, data=None, method=None):
    req=urllib.request.Request(a.base+path,data=data,method=method,headers={'Content-Type':'application/geo+json'})
    with opener.open(req,timeout=180) as r:
        return json.load(r)
raw=Path(a.dataset).read_bytes()  # Test fixture only; production uses streaming.
ref=json.loads(Path(a.reference).read_text(encoding='utf-8-sig'))
assert hashlib.sha256(raw).hexdigest()==ref['sha256'], 'Dataset fingerprint differs'
assert request('/actuator/health')['status']=='UP'
imp=request('/api/v1/imports',raw,'POST')
base='/api/v1/imports/'+imp['id']+'/geometry'
geo=request(base,b'','POST')
s=geo['statistics']
assert geo['status']=='READY' and s['objects']==ref['features']
assert abs(s['total_line_length_m']-ref['total_line_length_m'])<0.001
assert abs(s['total_polygon_area_m2']-ref['total_polygon_area_m2'])<0.01
assert request(base,b'','POST')['preparedAt']==geo['preparedAt']
objects=request(base+'/objects?limit=1000')
assert len(objects)==ref['features']
features=json.loads(raw)['features']
for feature,obj in zip(features,objects):
    assert json.loads(obj['id_json'])==feature['properties']['id']
    assert obj['object_type']==feature['properties']['object_type']
point=next(f['geometry']['coordinates'] for f in features if f['geometry']['type']=='Point')
near=request(base+f'/nearby?longitude={point[0]}&latitude={point[1]}&radiusM=100000&limit=1000')
assert len(near)==ref['features']
assert all(x['distance_m']<=y['distance_m'] for x,y in zip(near,near[1:]))
rel=request(base+'/relation?first=0&second=0')
assert rel['distance_m']==0 and rel['intersects'] and rel['first_covers_second']
api=request('/v3/api-docs')
assert '/api/v1/imports/{id}/geometry' in api['paths']
with opener.open(a.base+'/swagger-ui/index.html') as r: assert r.status==200
result={'status':'PASS','import':imp,'geometry':geo,'checks':['health','144 original IDs and types','independent length/area baseline','idempotence','nearby count and ordering','self relation','OpenAPI','Swagger'],'objects':len(objects),'nearby':len(near)}
Path(a.output).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
