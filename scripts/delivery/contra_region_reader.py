"""Bounded read-only Minecraft Anvil/NBT access for authoring already existing chunks."""
from pathlib import Path
import struct, io, zlib, gzip, json, sys
class Nbt:
 def __init__(self,b):self.f=io.BytesIO(b)
 def num(self,fmt):return struct.unpack('>'+fmt,self.f.read(struct.calcsize('>'+fmt)))[0]
 def string(self):return self.f.read(self.num('H')).decode('utf-8')
 def value(self,t):
  if t in [1,2,3,4,5,6]:return self.num({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
  if t==7:return self.f.read(self.num('i'))
  if t==8:return self.string()
  if t==9:
   sub=self.num('B');return[self.value(sub)for _ in range(self.num('i'))]
  if t==10:
   out={}
   while (sub:=self.num('B')):name=self.string();out[name]=self.value(sub)
   return out
  if t in [11,12]:return[self.num('i'if t==11 else'q')for _ in range(self.num('i'))]
  raise ValueError(('NBT tag',t))
 def root(self):t=self.num('B');self.string();return self.value(t)
def nbt(b):
 if b[:2]==b'\x1f\x8b':b=gzip.decompress(b)
 return Nbt(b).root()
class World:
 def __init__(self,path):self.path=Path(path);self.cache={}
 def chunk(self,x,z):
  key=x,z
  if key in self.cache:return self.cache[key]
  path=self.path/'region'/f'r.{x//32}.{z//32}.mca'
  if not path.exists():return None
  with path.open('rb')as f:
   f.seek(4*((x%32)+32*(z%32)));loc=int.from_bytes(f.read(4),'big');offset=loc>>8
   if not offset:return None
   f.seek(offset*4096);length=int.from_bytes(f.read(4),'big');compression=f.read(1)[0]
   if compression&128:raise ValueError('External chunk needs explicit handling')
   b=f.read(length-1)
  b=zlib.decompress(b)if compression==2 else gzip.decompress(b)if compression==1 else b
  o=Nbt(b).root();self.cache[key]=o;return o
 def state(self,x,y,z):
  c=self.chunk(x//16,z//16)
  if c is None:return None
  section=next((s for s in c['sections']if s['Y']==y//16),None)
  if not section or'block_states'not in section:return {'Name':'minecraft:air'}
  b=section['block_states'];pal=b['palette']
  if len(pal)==1:return pal[0]
  bits=max(4,(len(pal)-1).bit_length());per=64//bits;i=(x%16)+16*(z%16)+256*(y%16)
  at=(b['data'][i//per]>>(bits*(i%per)))&((1<<bits)-1)
  return pal[at]
 def surface(self,x,z):
  c=self.chunk(x//16,z//16)
  if c is None:return None
  hm=c['Heightmaps'].get('MOTION_BLOCKING_NO_LEAVES',c['Heightmaps']['WORLD_SURFACE']);i=(x%16)+16*(z%16);y=((hm[i//7]>>(9*(i%7)))&511)-64
  top=self.state(x,y-1,z);biomes={v for s in c['sections']if s['Y']==y//16 for v in s.get('biomes',{}).get('palette',[])}
  return {'x':x,'y':y,'z':z,'top':top['Name'],'biomes':sorted(biomes)}
if __name__=='__main__':
 w=World(sys.argv[1]);out=[]
 for x in range(2208,3489,64):
  for z in range(2400,3745,64):
   s=w.surface(x,z)
   if s:out.append(s)
 p=Path(sys.argv[2]);p.write_text(json.dumps({'read_only':True,'chunks_generated':0,'samples':out},indent=2));print('EXISTING_TERRAIN_SAMPLED',len(out),'water',sum(s['top']=='minecraft:water'for s in out),'chunks',len(w.cache))
