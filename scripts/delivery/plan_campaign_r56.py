"""Author-approved campaign preparation on the actual, closed R55 Contra copy.
No world writer: exports exact prior/new vanilla block states for native CAS apply.
"""
from pathlib import Path
import sys,json,uuid,heapq,zipfile,hashlib,math
from contra_region_reader import World,nbt
root=Path(sys.argv[1]);world=World(Path(sys.argv[2]));out=Path(sys.argv[3]);out.mkdir(parents=True,exist_ok=True)
mods=root/'r55-mods'
if not mods.exists():mods=root/'SHIPPED_SERVER/mods'
seed=nbt((world.path/'level.dat').read_bytes())['Data']['WorldGenSettings']['seed']
token=str(uuid.uuid5(uuid.UUID('74eb8e42-62b0-4375-9370-a2fee2fc89a0'),'PLS2-CAMPAIGN-R56'))
writes={};assets=[];sites=[];supports=[];zones=[];editable=[];frames=[];routes=[]
def state(s):return s['Name']+('['+','.join(k+'='+v for k,v in sorted(s.get('Properties',{}).items()))+']'if s.get('Properties')else'')
def point(x,y,z):return {'x':x,'y':y,'z':z}
def put(x,y,z,s):
 if not(-64<=y<320):raise ValueError('World height')
 prior=world.state(x,y,z)
 if prior is None:raise ValueError(('Absent chunk',x,z))
 writes[x,y,z]={'at':point(x,y,z),'expected':state(prior),'state':s}
def block(x,y,z):return writes.get((x,y,z),{}).get('state',state(world.state(x,y,z)))
def floor(x,y,z,r,material='minecraft:spruce_planks',clear=6):
 for dx in range(-r,r+1):
  for dz in range(-r,r+1):
   surface=world.surface(x+dx,z+dz)
   for fy in range(max(surface['y']-1,y-5),y):put(x+dx,fy,z+dz,'minecraft:cobblestone'if fy<y-1 else material)
   for fy in range(y,y+clear):put(x+dx,fy,z+dz,'minecraft:air')
def model(jar,path,x,y,z):
 jar=mods/jar
 with zipfile.ZipFile(jar)as archive:raw=archive.read(path)
 o=nbt(raw);assets.append({'jar':jar.name,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'resource':path,'resource_sha256':hashlib.sha256(raw).hexdigest(),'size':o['size'],'entities_copied':0,'loot_copied':False})
 for b in o['blocks']:
  s=o['palette'][b['state']]
  if s['Name']in ['minecraft:air','minecraft:structure_block','minecraft:jigsaw','minecraft:chest','minecraft:barrel','minecraft:spawner']:continue
  s=dict(s);s['Properties']=dict(s.get('Properties',{}))
  if'waterlogged'in s['Properties']:s['Properties']['waterlogged']='false'
  dx,dy,dz=b['pos'];put(x+dx,y+dy,z+dz,state(s))
def site(id,kind,x,y,z,r=5,player=False):
 sites.append({'id':id,'kind':kind,'anchor':point(x,y,z),'baselineState':''})
 zones.append({'id':id,'minX':x-r,'minY':y-6,'minZ':z-r,'maxX':x+r,'maxY':y+8,'maxZ':z+r,'playerBuild':player})
 return sites[-1]
def marker(id,siteid,x,y,z,action,quest=''):
 if any(s['at']==point(x,y,z)for s in supports):
  anchor=next(s['anchor']for s in sites if s['id']==siteid)
  for dx,dz in [(-4,0),(4,0),(0,-4),(-4,-2),(4,-2),(-2,-4),(2,-4)]:
   xx,zz=anchor['x']+dx,anchor['z']+dz
   if not any(s['at']==point(xx,y,zz)for s in supports):x,z=xx,zz;break
  else:raise ValueError('No distinct support position')
 put(x,y,z,'minecraft:lectern[facing=north,has_book=true,powered=false]')
 supports.append({'id':id,'site':siteid,'at':point(x,y,z),'block':'minecraft:lectern','action':action,'quest':quest})
def frame(siteid,x,y,z,slot=0,purpose='motif'):
 # The hanging cell faces north, backed by a solid authored wall one block south.
 put(x,y,z+1,'minecraft:stone_bricks');put(x,y,z,'minecraft:air')
 frames.append({'site':siteid,'uuid':str(uuid.uuid5(uuid.UUID(token),siteid+'/'+purpose+'/'+str(slot))),'slot':slot,'purpose':purpose,'at':point(x,y,z)})
def shelter(x,y,z,style='old'):
 floor(x,y,z,5,'minecraft:spruce_planks'if style=='old'else'minecraft:stone_bricks')
 for dx in [-4,4]:
  for dz in [-4,4]:
   for dy in range(4):put(x+dx,y+dy,z+dz,'minecraft:stripped_spruce_log[axis=y]')
 for dx in range(-5,6):
  for dz in range(-5,6):put(x+dx,y+4+(abs(dx)<3),z+dz,'minecraft:spruce_slab[type=bottom,waterlogged=false]'if style=='old'else'minecraft:cut_copper')
 for dx in range(-4,5):
  for dy in range(3):put(x+dx,y+dy,z+4,'minecraft:oak_planks'if style=='old'else'minecraft:stone_bricks')
 for dx in [-3,3]:put(x+dx,y+2,z+3,'minecraft:lantern[hanging=false,waterlogged=false]')
def bed(x,y,z):
 put(x,y,z,'minecraft:red_bed[facing=north,occupied=false,part=foot]');put(x,y,z-1,'minecraft:red_bed[facing=north,occupied=false,part=head]')
# Arrival: a real degraded model beside an actual sea shore, with a common safe deck.
site('s2_arrival','WRECK',2905,65,2784,19)
model('lithostitched-1.8.0-neoforge-21.1.jar','overlay.breaks_seed_parity/data/minecraft/structure/shipwreck/rightsideup_backhalf_degraded.nbt',2896,61,2768)
floor(2905,65,2784,2,clear=3)
put(2908,64,2786,'minecraft:oak_planks');frame('s2_arrival',2902,67,2787);marker('arrival_motif','s2_arrival',2908,65,2786,'examine wreck_motif')
site('s2_shore','SAFE_SHORE',2928,69,2784,6);floor(2928,69,2784,3,'minecraft:gravel',3)
marker('shore_arrival','s2_shore',2931,69,2784,'reach_shore','P0')
# The player's camp keeps editable surroundings and empty ordinary storage.
site('s2_camp','CAMP',2916,92,2946,6,True);shelter(2916,92,2946)
bed(2919,92,2948);put(2916,92,2949,'minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]')
for i in range(3):put(2915+i,91,2946,'minecraft:barrel[facing=up,open=false]')
marker('camp_claim','s2_camp',2913,92,2944,'claim_camp','P1')
marker('camp_arrangement','s2_camp',2919,92,2944,'campaign record_arrangement','B2')
marker('camp_scene','s2_camp',2913,92,2948,'campaign observe_scene','D1')
frame('s2_camp',2919,94,2949,0,'memory_local')
# A clear high point, preserving the approved three-fire rule and a real inward bearing.
site('s2_signal','SIGNAL',2896,92,2910,11);floor(2896,92,2910,9,'minecraft:gravel',20)
for dx in [-4,0,4]:put(2896+dx,92,2914,'minecraft:campfire[facing=north,lit=false,signal_fire=false,waterlogged=false]')
marker('signal_request','s2_signal',2899,92,2907,'activate_distress_signal','P2')
site('s2_old_camp','OLD_CAMP',2896,88,3142,7);shelter(2896,88,3142)
model('YungsBetterJungleTemples-1.21.1-NeoForge-3.1.2.jar','data/betterjungletemples/structure/props/prop_campsite_0.nbt',2897,88,3144)
bed(2893,88,3145);frame('s2_old_camp',2892,90,3145)
marker('old_camp_motif','s2_old_camp',2893,88,3140,'examine camp_motif')
site('s2_archive','ARCHIVE',2932,91,3496,7);shelter(2932,91,3496,'archive')
site('s2_old_shelter','OLD_SHELTER',2758,95,3242,7);shelter(2758,95,3242)
model('YungsExtras-1.21.1-NeoForge-5.1.1.jar','data/yungsextras/structure/swamp/cubby/swamp_cubby_0.nbt',2754,94,3244)
for i in range(3):
 put(2757+i,94,3242,'minecraft:barrel[facing=up,open=false]');supports.append({'id':'trace_slot_'+str(i),'site':'s2_old_shelter','at':point(2757+i,94,3242),'block':'minecraft:barrel','action':'campaign ancient_arrangement','quest':'B3'});frame('s2_old_shelter',2757+i,96,3243,i,'arrangement')
marker('ancient_arrangement','s2_old_shelter',2761,95,3240,'campaign ancient_arrangement','B3')
site('s2_perception','PERCEPTION',2868,91,2958,10);floor(2868,91,2958,9,'minecraft:mossy_stone_bricks',4)
marker('scene_start','s2_perception',2865,91,2956,'scene','C1')
concepts=['ICI','VOIR','LIER','AILLEURS','ENTENDRE','REPETER','PARTIR']
for i,name in enumerate(concepts):
 x,z=2862+(i%4)*4,2961+(i//4)*3;put(x,91,z,'minecraft:chiseled_stone_bricks');marker('concept_'+name.lower(),'s2_perception',x,92,z,'sequence:'+name,'C1')
# A small existing-asset footbridge and an actual lateral route share a bounded sector.
floor(2818,102,3342,7,'minecraft:gravel',4)
model('YungsBridges-1.21.1-NeoForge-5.1.1.jar','data/yungsbridges/structure/bridge/wood/13_0_broken.nbt',2817,101,3336)
for id,kind,x,z,option in [('s2_repair_a','PASSAGE',2818,3338,'REPAIR'),('s2_repair_b','PASSAGE',2818,3346,'REPAIR'),('s2_detour_a','DETOUR',2834,3338,'DETOUR'),('s2_detour_b','DETOUR',2834,3346,'DETOUR')]:
 y=102 if kind=='PASSAGE'else world.surface(x,z)['y']+1
 site(id,kind,x,y,z,4);floor(x,y,z,2,'minecraft:oak_planks',3)
 put(x,y-1,z,'minecraft:cracked_stone_bricks'if kind=='PASSAGE'else'minecraft:coarse_dirt');editable.append({'at':point(x,y-1,z),'option':option})
 marker(id+'_explore',id,x-2,y,z,'campaign explore '+option,'D3')
 marker(id+'_work',id,x+2,y,z,'campaign work '+option,'D3')
put(2816,102,3339,'minecraft:chest[facing=north,type=single,waterlogged=false]');put(2819,102,3339,'minecraft:chain[axis=y,waterlogged=false]')
supports.append({'id':'departure_trace','site':'s2_repair_a','at':point(2816,102,3339),'block':'minecraft:chest','action':'inspect departure_trace','quest':'D2'})
site('s2_signal_region_a','SIGNAL_REGION',2868,91,2980,4);floor(2868,91,2980,3,'minecraft:gravel',3)
site('s2_signal_region_b','SIGNAL_REGION',2984,96,3386,4);floor(2984,96,3386,3,'minecraft:gravel',3)
for id in ['s2_signal_region_a','s2_signal_region_b']:
 s=next(v for v in sites if v['id']==id);p=s['anchor'];marker(id+'_listen',id,p['x']+2,p['y'],p['z'],'campaign listen','E1');marker(id+'_record',id,p['x']-2,p['y'],p['z'],'campaign record_signal','E1')
site('s2_transmitter','TRANSMITTER',3030,80,3466,7);shelter(3030,80,3466,'archive')
repair=point(3034,81,3466);indicator=point(3030,83,3469)
put(**{'x':repair['x'],'y':repair['y'],'z':repair['z'],'s':'minecraft:oak_planks'});put(indicator['x'],indicator['y'],indicator['z'],'minecraft:purple_stained_glass')
for dy in range(1,7):put(3034,80+dy,3469,'minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]')
for i,action in enumerate(['campaign transmit FILTER','campaign transmit AMPLIFY','campaign repair_transmitter','campaign record_transmission']):
 marker('transmitter_action_'+str(i),'s2_transmitter',3027+2*i,80,3464,action,'E3'if i<2 else'R1'if i==2 else'F2')
site('s2_retreat','RETREAT',3032,86,3370,6);shelter(3032,86,3370)
marker('retreat_action','s2_retreat',3035,86,3370,'campaign reach_retreat','R1')
site('s2_memory','MEMORY',2932,92,2962,5);floor(2932,92,2962,4,'minecraft:stone_bricks',4)
marker('memory_journal','s2_memory',2929,92,2962,'journal','F3');frame('s2_memory',2932,94,2965,0,'memory_central')
frame('s2_archive',2935,93,3499,0,'memory_local');frame('s2_old_shelter',2762,97,3245,0,'memory_local')
# Place real readable supports for every existing data-driven investigation asset.
content=json.loads((root/'investigation.json').read_text(encoding='utf-8'))
offsets=[(-3,-2),(-1,-3),(1,-3),(3,-2),(-3,1),(3,1),(-2,2),(2,2)]
counts={}
for asset in content['supports']:
 if asset['id']=='departure_trace':continue
 s=next(s for s in sites if s['kind']==asset['siteKind']);i=counts.get(s['id'],0);counts[s['id']]=i+1;dx,dz=offsets[i];p=s['anchor']
 marker('document_'+asset['id'],s['id'],p['x']+dx,p['y'],p['z']+dz,'inspect '+asset['id'],asset['quest'])
# Connected paths use existing terrain and avoid existing protected location footprints.
g0=json.loads((world.path/'director-location-imports/contra-saison2.json').read_text());existing=[v['bounds']for v in g0['locations']]
def forbidden(x,z):return any(b['min_x']-8<=x<=b['max_x_exclusive']+8 and b['min_z']-8<=z<=b['max_z_exclusive']+8 for b in existing)
def walk(a,b):
 start=(round(a['x']/4)*4,round(a['z']/4)*4);goal=(round(b['x']/4)*4,round(b['z']/4)*4);queue=[(0,start)];cost={start:0};previous={};limit=0
 while queue and limit<12000:
  _,p=heapq.heappop(queue);limit+=1
  if p==goal:break
  here=world.surface(*p)
  if here is None:continue
  for dx,dz in [(4,0),(-4,0),(0,4),(0,-4)]:
   q=p[0]+dx,p[1]+dz
   if forbidden(*q):continue
   s=world.surface(*q)
   if not s or s['top']in ['minecraft:water','minecraft:kelp','minecraft:tall_seagrass','minecraft:seagrass']:continue
   slope=abs(s['y']-here['y'])
   if slope>5:continue
   penalty=4+slope*3+(25 if 'wood'in s['top']or'log'in s['top']else 0)
   n=cost[p]+penalty
   if n<cost.get(q,1e12):cost[q]=n;previous[q]=p;heapq.heappush(queue,(n+abs(q[0]-goal[0])+abs(q[1]-goal[1]),q))
 if goal not in cost:raise ValueError(('No terrain path',a,b))
 coarse=[goal]
 while coarse[-1]!=start:coarse.append(previous[coarse[-1]])
 coarse.reverse();line=[]
 for p,q in zip(coarse,coarse[1:]):
  for i in range(4):
   x=p[0]+(q[0]-p[0])*i//4;z=p[1]+(q[1]-p[1])*i//4;h=world.surface(x,z)
   if h is None:raise ValueError('Absent path column')
   line.append(point(x,h['y'],z))
 routes.append({'from':a,'to':b,'points':line})
 # Sparse low-impact trail cues: grass paths and safe steps, not a flattened world strip.
 for p in line:
  x,y,z=p['x'],p['y'],p['z']
  if any(v['minX']<=x<=v['maxX']and v['minZ']<=z<=v['maxZ']for v in zones):continue
  old=world.state(x,y-1,z)['Name']
  if old in ['minecraft:grass_block','minecraft:podzol','minecraft:moss_block','minecraft:coarse_dirt']:put(x,y-1,z,'minecraft:dirt_path')
def get(id):return next(s['anchor']for s in sites if s['id']==id)
for a,b in [('s2_shore','s2_camp'),('s2_camp','s2_signal'),('s2_camp','s2_old_camp'),('s2_old_camp','s2_archive'),('s2_old_camp','s2_old_shelter'),('s2_old_camp','s2_perception'),('s2_old_shelter','s2_repair_a'),('s2_repair_b','s2_archive'),('s2_archive','s2_transmitter'),('s2_transmitter','s2_retreat'),('s2_camp','s2_memory'),('s2_perception','s2_signal_region_a'),('s2_retreat','s2_signal_region_b')]:walk(get(a),get(b))
# Safe visible common departure from the deck to the natural shore.
for x in range(2905,2930):
 y=65+min(4,max(0,(x-2908)//4))
 for z in range(2783,2786):put(x,y-1,z,'minecraft:oak_planks');put(x,y,z,'minecraft:air');put(x,y+1,z,'minecraft:air')
# Gentle entrances meet the existing terrain instead of leaving isolated raised pads.
for s in sites:
 if s['kind']in ['WRECK','SIGNAL_REGION','PASSAGE','DETOUR']:continue
 p=s['anchor'];r=5 if s['kind']not in ['SAFE_SHORE','MEMORY']else 3
 for distance in range(1,7):
  z=p['z']-r-distance;natural=world.surface(p['x'],z)['y']-1
  y=max(natural,p['y']-1-distance)
  for dx in [-1,0,1]:
   put(p['x']+dx,y,z,'minecraft:spruce_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')
   put(p['x']+dx,y+1,z,'minecraft:air');put(p['x']+dx,y+2,z,'minecraft:air')
# Keep all anchors genuinely occupiable after decoration, and retain initial work baselines.
for s in sites:
 p=s['anchor']
 for dx in [-1,0,1]:
  for dz in [-1,0,1]:put(p['x']+dx,p['y'],p['z']+dz,'minecraft:air');put(p['x']+dx,p['y']+1,p['z']+dz,'minecraft:air')
 s['baselineState']=block(p['x'],p['y']-1,p['z'])
manifest={'schema':1,'authorDecision':'PLS2-S2-AUTHOR-20261123-CAMPAIGN-IMPLANTATION','worldToken':token,'worldSeed':seed,'sites':sites,'supports':supports,'zones':zones,'editable':editable,'frames':frames,'repairPoint':repair,'signalIndicator':indicator}
for s in supports:
 if not block(s['at']['x'],s['at']['y'],s['at']['z']).startswith(s['block']+'['):raise ValueError(('Support overwritten by decoration',s['id']))
changed=[v for v in writes.values()if v['state']!=v['expected']]
if len(changed)>60000:raise ValueError('Authoring bound exceeded')
plan={'schema':1,'baseline':'R55/a26225df78e63f819b9ce4a8d758947d46541f6e','sourceDirectorWorld':'74eb8e42-62b0-4375-9370-a2fee2fc89a0','manifest':manifest,'writes':changed,'modelAssets':assets,'routes':routes,'canonicalCampaignSha256':hashlib.sha256((root/'campaign.json').read_bytes()).hexdigest(),'investigationAssetsSha256':hashlib.sha256((root/'investigation.json').read_bytes()).hexdigest(),'noNewChunks':True,'noNarrativeEdits':True}
(out/'AUTHORING_PLAN.json').write_text(json.dumps(plan,separators=(',',':')),encoding='utf-8')
(out/'SITES_AND_ROUTES.json').write_text(json.dumps({'manifest':manifest,'routes':routes,'modelAssets':assets},indent=2),encoding='utf-8')
print('ACTUAL_R55_AUTHORING_PLAN_READY',len(sites),'sites',len(supports),'physical_supports',len(changed),'bounded_changes',len(routes),'routes')
