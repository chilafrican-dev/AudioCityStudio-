#!/usr/bin/env python3
"""Rebuild the curated CC0 asset bank. Requires ffmpeg and Python 3."""
import concurrent.futures,hashlib,json,re,subprocess,urllib.request,urllib.parse,wave
from pathlib import Path
BASE=Path(__file__).resolve().parents[1]; OUT=BASE/'assets/samples'; LIC=BASE/'assets/licenses'; CACHE=BASE/'build/sample-downloads'; OUT.mkdir(parents=True,exist_ok=True);LIC.mkdir(parents=True,exist_ok=True);CACHE.mkdir(parents=True,exist_ok=True)
REV='c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e'
I='Idiophones/Struck Idiophones/';M='Membranophones/Struck Membranophones/'
choices=[
 ('conga_open','Conga / open',M+'Conga/Conga_HitN_v3_rr1_Sum.wav','Percussion'),
 ('conga_muted','Conga / muted',M+'Conga/Conga_HitFM_v2_rr1_Sum.wav','Percussion'),
 ('tumba_low','Tumba / low open',M+'Conga/Tumba_HitN_v3_rr1_Sum.wav','Percussion'),
 ('quinto_high','Quinto / high open',M+'Conga/Quinto_HitN_v3_rr1_Sum.wav','Percussion'),
 ('bongo_high','Bongo / high',M+'Bongos/BongoH_Hit1_v3_rr1_Mid.wav','Percussion'),
 ('bongo_low','Bongo / low',M+'Bongos/BongoL_Hit1_v3_rr1_Mid.wav','Percussion'),
 ('bongo_high_mute','Bongo / high muted',M+'Bongos/BongoH_HitMuted1_v3_rr1_Mid.wav','Percussion'),
 ('bongo_low_mute','Bongo / low muted',M+'Bongos/BongoL_HitMuted2_v3_rr1_Mid.wav','Percussion'),
 ('shaker_large','Shaker / large hit',I+'Shaker, Large/LShaker_Hit_rr1_Mid.wav','Percussion'),
 ('shaker_small','Shaker / double down',I+'Shaker, Small/Mid_ShakerDouble_Down_rr1.wav','Percussion'),
 ('cabasa_hit','Cabasa / hit',I+'Cabasa/Cabasa1_Hit_rr1_Mid.wav','Percussion'),
 ('cabasa_rub','Cabasa / rub',I+'Cabasa/Cabasa1_Rub_v2_rr1_Mid.wav','Percussion'),
 ('clave_light','Claves / light',I+'Claves/Claves1_Hit_v2_rr1_Mid.wav','Percussion'),
 ('clave_dark','Claves / dark',I+'Claves/Claves2_Hit_v3_rr1_Mid.wav','Percussion'),
 ('cowbell_open','Cowbell / open',I+'Cowbells/Cowbell1_Normal_v3_rr1_Mid.wav','Percussion'),
 ('cowbell_muted','Cowbell / muted',I+'Cowbells/Cowbell2_Muted_v3_rr1_Mid.wav','Percussion'),
 ('clap_hand','Hand clap / group',I+'Claps/Clap_rr1.wav','Drums'),
 ('clap_solo','Hand clap / solo',I+'Claps/SoloClap_vl2.wav','Drums'),
 ('hat_closed','Hi-hat / closed',I+'Hi-Hat Cymbal/HiHat_HitC_v3_rr1_Mid.wav','Drums'),
 ('hat_open','Hi-hat / open',I+'Hi-Hat Cymbal/HiHat_HitO_rr1_Mid.wav','Drums'),
 ('tambourine_hit','Tambourine / hit',I+'Tambourine 1/Tamb1_Hit_v2_rr1_Mid.wav','Percussion'),
 ('tambourine_shake','Tambourine / shake',I+'Tambourine 1/Tamb1_Shake_rr1_Mid.wav','Percussion'),
 ('kick_acoustic','Acoustic bass drum / short',M+'Bass Drum 1/BDrumNew_hit_v3_rr1_Sum.wav','Drums'),
 ('snare_acoustic','Snare / acoustic',M+'Snare Drum, Modern 1/Snare2_HitSN_v5_rr1_Mid.wav','Drums')]
def fetch(url,path):
 if not path.exists():
  req=urllib.request.Request(url,headers={'User-Agent':'AudioCityStudio-SampleAssetBuilder/0.3'})
  with urllib.request.urlopen(req,timeout=50) as r:path.write_bytes(r.read())
 return path.read_bytes()
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def convert(raw,out,duration=None,offset=0,loop=False):
 filters=[]
 if offset:filters.append(f'atrim=start={offset:.9f}')
 filters.append('asetpts=PTS-STARTPTS')
 if not loop:filters.append('silenceremove=start_periods=1:start_threshold=-60dB:start_silence=0.002')
 filters+=['aresample=48000','aformat=sample_fmts=flt:channel_layouts=stereo','loudnorm=I=-20:TP=-3:LRA=7']
 if duration:filters+=['apad',f'atrim=duration={duration:.9f}']
 elif not loop:filters+=['atrim=duration=3']
 # Very short fades remove boundary clicks; peak limiting keeps each source below full scale.
 filters+=['afade=t=in:st=0:d=0.001']
 if duration:filters.append(f'afade=t=out:st={max(0,duration-.002):.9f}:d=0.002')
 else:filters.append('areverse');filters.append('afade=t=in:st=0:d=0.008') if not duration else None
 if not duration:filters.append('areverse')
 filters.append('alimiter=limit=0.707:level=false:latency=true')
 if duration:filters+=['aresample=48000','apad',f'atrim=end_sample={round(duration*48000)}']
 subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(raw),'-af',','.join(filters),'-ar','48000','-ac','2','-c:a','pcm_s16le',str(out)],check=True)
 with wave.open(str(out),'rb') as w:
  assert w.getframerate()==48000 and w.getnchannels()==2 and w.getsampwidth()==2
  return w.getnframes()
def one(choice):
 id,title,path,category=choice;url='https://raw.githubusercontent.com/sgossner/VCSL/'+REV+'/'+urllib.parse.quote(path);raw=CACHE/(id+'.wav');fetch(url,raw)
 frames=convert(raw,OUT/(id+'.wav'),duration=.6 if id=='kick_acoustic' else None)
 return dict(id=id,name=title,file=id+'.wav',category=category,kind='One-shot',bpm=0,bars=0,frames=frames,creator='Versilian Studios / VCSL contributors',source='https://github.com/sgossner/VCSL/blob/'+REV+'/'+urllib.parse.quote(path),download=url,license='CC0-1.0',licenseUrl='https://creativecommons.org/publicdomain/zero/1.0/',quality='Original lossless WAV source; trimmed, levelled and converted to 48 kHz / 16-bit stereo',sourceSha256=sha(raw),assetSha256=sha(OUT/(id+'.wav')))
def loop(id,title,creator,source,bpm,parts):
 html=fetch(source,CACHE/(id+'.html')).decode();assert 'creativecommons.org/publicdomain/zero/1.0' in html
 links=re.findall(r'https://cdn\.freesound\.org/previews/[^\s"<>]+-hq\.mp3',html);assert links,'Missing public HQ preview'
 raw=CACHE/(id+'.mp3');fetch(links[0],raw);result=[];duration=4*4*60/bpm
 for part in range(parts):
  name=id+('_'+chr(97+part) if parts>1 else '');frames=convert(raw,OUT/(name+'.wav'),duration,part*duration,True)
  result.append(dict(id=name,name=title+(' / '+chr(65+part) if parts>1 else ''),file=name+'.wav',category='Loops',kind='Loop',bpm=bpm,bars=4,frames=frames,creator=creator,source=source,download=links[0],license='CC0-1.0',licenseUrl='https://creativecommons.org/publicdomain/zero/1.0/',quality='Freesound HQ MP3 preview source, not original lossless WAV; four-bar excerpt, levelled and converted to 48 kHz / 16-bit stereo',sourceSha256=sha(raw),assetSha256=sha(OUT/(name+'.wav'))))
 return result
if __name__=='__main__':
 fetch('https://raw.githubusercontent.com/sgossner/VCSL/'+REV+'/LICENSE',LIC/'VCSL-CC0.txt')
 fetch('https://raw.githubusercontent.com/sgossner/VCSL/'+REV+'/README.md',LIC/'VCSL-README.md')
 with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:entries=list(pool.map(one,choices))
 entries=loop('afro_bassam','Afrobeat / Bassam','NeguS1993','https://freesound.org/people/NeguS1993/sounds/586917/',100,1)+loop('afro_nova','Afrobeat / Old School','NovaSoundTechnology / G Nova','https://freesound.org/people/NovaSoundTechnology/sounds/731085/',99,4)+entries
 manifest={'schema':1,'title':'Audio City / Afrobeat Toolkit','license':'CC0-1.0','retrieved':'2026-11-13','vcslCommit':REV,'sounds':entries}
 (OUT/'manifest.json').write_text(json.dumps(manifest,indent=2))
 credits=['# Internet sample credits','', 'All bundled third-party samples below were identified by their original publishers as CC0 1.0. Attribution is not required; names and source URLs are retained for provenance. The sample source pages, not Audio City, identify the original recordings.','', '## VCSL','Versilian Studios explicitly permits use of VCSL samples in commercial software and DAWs: https://versilian-studios.com/vcsl/','Repository: https://github.com/sgossner/VCSL','Pinned revision: '+REV,'The complete upstream CC0 text is in VCSL-CC0.txt.','', '## Freesound loops','The two source pages display Creative Commons 0 and permit copying, modification and redistribution, including commercially. The bundled versions derive from publicly offered high-quality MP3 previews; they are not lossless originals. The 99 BPM Nova source was divided into four four-bar excerpts.','', '## Processing','Trimmed, levelled, resampled to 48 kHz, and encoded as 16-bit stereo WAV. Loop lengths are aligned to their stated BPM and bar count. Very short edge fades remove clicks.','', '| Name | Creator | License | Source |','| --- | --- | --- | --- |']
 for e in entries:credits.append('| '+e['name']+' | '+e['creator']+' | CC0-1.0 | '+e['source']+' |')
 (LIC/'SAMPLE-CREDITS.md').write_text('\n'.join(credits)+'\n')
 print(json.dumps({'sounds':len(entries),'loops':sum(e['kind']=='Loop' for e in entries),'bytes':sum((OUT/e['file']).stat().st_size for e in entries)}))
