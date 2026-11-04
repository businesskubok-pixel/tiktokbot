'use strict';
const fs=require('fs');
const WebSocket=require('ws');
const cfg=JSON.parse(fs.readFileSync('./config.json','utf8'));
if(!cfg.token || cfg.token.length<20 || cfg.token.startsWith('CHANGE_ME'))throw new Error('Ustaw taki sam, losowy token w pluginie, bridge oraz bocie.');
const recent=new Map();let likeRemainder=0;
const giftProgress=new Map(); // gift+user -> pozostale sztuki do kolejnego progu

let sentSecond=0,sentThisSecond=0;
function nick(d){return String(d.uniqueId||d.nickname||d.userId||'Widz').replace(/[|\r\n]/g,' ').slice(0,60)}
function seen(id,ttl=300000){
  if(!id)return false;
  const t=Date.now();if(recent.has(id) && t-recent.get(id)<ttl)return true;
  recent.set(id,t);if(recent.size>3000){for(const [k,v] of recent)if(t-v>ttl)recent.delete(k);}
  return false;
}
async function send(event,username){
  const second=Math.floor(Date.now()/1000);
  if(second!==sentSecond){sentSecond=second;sentThisSecond=0}
  if(++sentThisSecond>Math.max(1,cfg.maxEventsPerSecond||10)){console.warn('[BRIDGE] Limit zdarzen/s, pomijam',event);return}
  try{
    const response=await fetch(cfg.pluginHttp,{method:'POST',headers:{'X-Rodeo-Token':cfg.token,'Content-Type':'text/plain;charset=utf-8'},body:event+'|'+username,signal:AbortSignal.timeout(4000)});
    console.log('[EVENT]',event,username,response.status);
  }catch(e){console.warn('[BRIDGE] Brak polaczenia z pluginem:',e.message)}
}
function connect(){
  const ws=new WebSocket(cfg.tikfinityWebSocket);
  ws.on('open',()=>console.log('[TikFinity] Polaczono. Obsluga: gift, follow, like, share.'));
  ws.on('message',raw=>{
    let msg;try{msg=JSON.parse(raw.toString())}catch{return}
    const d=msg.data;if(!d || typeof d!=='object')return;
    const user=nick(d), event=String(msg.event||'').toLowerCase();
    if(event==='gift'){
      if(d.giftType===1 && d.repeatEnd===false)return;
      // Obsluguje stare wartosci "rose" oraz nowe obiekty {event,label,coinsLabel}.
      const match=cfg.giftNames?.[d.giftName] || cfg.giftNames?.[String(d.giftName||'').toLowerCase()];
      if(!match){console.log('[GIFT] Nieznany prezent:',d.giftName,'- dodaj do config.json');return;}
      const eventType=typeof match==='string' ? match : match.event;
      if(!eventType)return;
      if(typeof match==='object')console.log('[GIFT]',d.giftName,'->',match.label||eventType,'| cena:',match.coinsLabel||'nie podano');
      const id=String(d.msgId||d.messageId||'');
      if(seen('gift:'+id))return;
      // Dla Finger Heart liczmy SZTUKI prezentow od konkretnego widza (a nie monety).
      // Finalny event streaku niesie repeatCount; wczesniejsze update'y pomijamy.
      // Limit czasu postepu zapobiega bezterminowemu kumulowaniu prezentow.
      const perTrigger=typeof match==='object' ? Number(match.giftsPerTrigger||1) : 1;
      if(Number.isInteger(perTrigger) && perTrigger>1 && perTrigger<=100){
        const userKey=String(d.userId||d.uniqueId||user);
        const stateKey=eventType+':'+userKey;
        const now=Date.now();
        const prior=giftProgress.get(stateKey);
        const oldCount=prior && now-prior.at<24*60*60*1000 ? prior.count : 0;
        // repeatCount is the FINAL streak total for repeatable gifts.
        const units=d.giftType===1 ? Math.max(1,Math.min(1000,Math.floor(Number(d.repeatCount)||1))) : 1;
        const total=oldCount+units;
        const times=Math.floor(total/perTrigger);
        giftProgress.set(stateKey,{count:total%perTrigger,at:now});
        for(let i=0;i<Math.min(times,20);i++)void send(eventType,user);
        console.log('[GIFT PROGRESS]',user,eventType,(total%perTrigger)+'/'+perTrigger);
      }else{
        void send(eventType,user);
      }
    }else if(event==='follow'){
      // Follow notifications can repeat on reconnect; debounce user for 5 min.
      if(seen('follow:'+String(d.userId||d.uniqueId||user),300000))return;
      void send('follow',user);
    }else if(event==='share'){
      if(seen('share:'+String(d.msgId||d.messageId||Date.now()+user)))return;
      void send('share',user);
    }else if(event==='like'){
      // TikTok-Live-Connector: likeCount = increment of likes in THIS event.
      // totalLikeCount is cumulative and MUST NOT be added repeatedly.
      const count=Math.max(0,Math.min(5000,Math.floor(Number(d.likeCount ?? d.count ?? 0))||0));
      if(count<=0)return;
      likeRemainder+=count;
      const threshold=Math.max(50,Math.min(10000,Number(cfg.likesPerZombie)||500));
      let triggered=0;
      while(likeRemainder>=threshold && triggered<10){
        likeRemainder-=threshold;triggered++;
        void send('likes500',user);
      }
      console.log('[LIKE]',user,'+',count,'postep:',likeRemainder+'/'+threshold);
    }
  });
  ws.on('error',err=>console.warn('[TikFinity]',err.message));
  ws.on('close',()=>{console.warn('[TikFinity] Rozlaczono. Ponawiam za 5 s.');setTimeout(connect,5000)});
}
connect();
