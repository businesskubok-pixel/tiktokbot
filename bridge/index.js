'use strict';
const fs=require('fs'); const WebSocket=require('ws');
const cfg=JSON.parse(fs.readFileSync('./config.json','utf8'));
if(!cfg.token || cfg.token.startsWith('CHANGE_ME')) throw new Error('Zmien token w config.json oraz w pluginie');
let recent=[]; let lastSecond=0, used=0;
function connect(){
  const ws=new WebSocket(cfg.tikfinityWebSocket);
  ws.on('open',()=>console.log('[TikFinity] Polaczono'));
  ws.on('message',async data=>{
    let msg; try{msg=JSON.parse(data.toString())}catch{return}
    if(msg.event!=='gift' || !msg.data)return;
    const d=msg.data;
    // Zdarzenia streakow bywaja wysylane w trakcie kombinacji: liczymy tylko final.
    if(d.giftType===1 && d.repeatEnd===false)return;
    const gift=cfg.giftNames[d.giftName];
    if(!gift)return;
    const nickname=String(d.uniqueId || d.nickname || d.userId || 'Widz').replace(/[|\r\n]/g,' ').slice(0,60);
    const eventId=String(d.msgId || d.messageId || '');
    if(eventId && recent.includes(eventId))return;
    if(eventId)recent.push(eventId); if(recent.length>300)recent.shift();
    const now=Math.floor(Date.now()/1000);
    if(now!==lastSecond){lastSecond=now;used=0}
    if(++used>Math.max(1,cfg.maxGiftsPerSecond||5))return;
    try{
      const response=await fetch(cfg.pluginHttp, {method:'POST',headers:{'X-Rodeo-Token':cfg.token,'Content-Type':'text/plain'},body:gift+'|'+nickname,signal:AbortSignal.timeout(4000)});
      console.log('[GIFT]',gift,nickname,response.status);
    }catch(err){console.warn('[BRIDGE] Brak polaczenia z pluginem:',err.message)}
  });
  ws.on('error',err=>console.warn('[TikFinity]',err.message));
  ws.on('close',()=>{console.log('[TikFinity] Ponawiam polaczenie');setTimeout(connect,5000)});
}
connect();
