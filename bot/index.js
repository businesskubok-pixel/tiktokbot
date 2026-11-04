'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const mineflayer = require('mineflayer');
const configPath=path.join(__dirname,'config.json');
const cfg=JSON.parse(fs.readFileSync(configPath,'utf8'));
console.log('[BOT] Konfiguracja:',configPath);
console.log('[BOT] Serwer:',cfg.host+':'+cfg.port,'API:',cfg.pluginStatusUrl);
console.log('[BOT] Token skonfigurowany:',typeof cfg.token==='string' && cfg.token.length>=20 ? 'TAK' : 'NIE');
let activeToken=cfg.token;
function tokenFingerprint(t){return crypto.createHash('sha256').update(String(t)).digest('hex').slice(0,12)}
console.log('[BOT] Odcisk tokenu SHA256:',tokenFingerprint(activeToken),'(bez ujawniania tokenu)');
function currentToken(){
  try{
    const next=JSON.parse(fs.readFileSync(configPath,'utf8')).token;
    if(typeof next!=='string'||next.length<20)throw new Error('token musi mieć 20+ znaków');
    if(activeToken!==next){
      activeToken=next;
      console.log('[BOT] Wykryto zmianę tokenu w config.json; nowy odcisk:',tokenFingerprint(next));
    }
    return next;
  }catch(e){console.error('[BOT] Nie mogę odczytać aktualnego tokenu:',e.message);return null}
}

const directions = { EAST:[1,0], WEST:[-1,0], SOUTH:[0,1], NORTH:[0,-1] };
let reconnectTimer;
function connect() {
  const bot = mineflayer.createBot({
    host:cfg.host || '127.0.0.1', port:cfg.port || 25565,
    username:cfg.username || 'RodeoBot', auth:cfg.auth || 'offline',
    version:cfg.version || '1.20.6', respawn:false
  });
  let alive=true, started=false, running=false, direction=(cfg.direction || 'EAST').toUpperCase();
  let walkTimer, statusTimer, respawnTimer, lastHit=0, currentTargetId=null, targetStart=0, lastTargetLog=0;
  let lastStatusMessage='';
  function logStatus(message){if(message!==lastStatusMessage){console.log('[BOT] API:',message);lastStatusMessage=message;}}
  function stopMovement(){ try {bot.setControlState('forward',false);bot.setControlState('sprint',false);bot.setControlState('jump',false);}catch{} }
  function stopIntervals(){for(const id of [walkTimer,statusTimer])if(id)clearInterval(id);walkTimer=statusTimer=null;}
  async function status(){
    const token=currentToken();
    if(!cfg.pluginStatusUrl || !token){logStatus('Brak pluginStatusUrl lub poprawnego tokenu w '+configPath);return;}
    try {
      const res=await fetch(cfg.pluginStatusUrl,{headers:{'X-Rodeo-Token':token},signal:AbortSignal.timeout(1800)});
      if(!res.ok)throw new Error('HTTP '+res.status+(res.status===403?' — odcisk tokenu bota: '+tokenFingerprint(token)+'; sprawdź czy serwer wczytał tę samą wartość':''));
      const value=await res.json();
      running=Boolean(value.running);
      logStatus('HTTP 200, LIVE '+(running?'WŁĄCZONY':'WYŁĄCZONY')+', świat '+value.world);
      if(value.direction && directions[value.direction.toUpperCase()])direction=value.direction.toUpperCase();
      if(!running)stopMovement();
    }catch(err){
      running=false;stopMovement();
      if(started)logStatus('BŁĄD: '+err.message);
    }
  }
  const hostileNames=new Set(['zombie','husk','drowned','skeleton','stray','bogged','wither_skeleton','creeper','spider','cave_spider','witch','pillager','ravager','evoker','warden','vindicator','vex']);
  function isHostile(entity){
    if(!entity || !entity.position || !bot.entity || entity.id===bot.entity.id)return false;
    const name=String(entity.name || entity.displayName || '').toLowerCase().replace(/^minecraft:/,'').replace(/\s+/g,'_');
    return hostileNames.has(name) || hostileNames.has(String(entity.name || '').toLowerCase());
  }
  function move(){
    if(!started || !bot.entity || !alive)return;
    if(!running){currentTargetId=null;stopMovement();return;}
    const now=Date.now(), pos=bot.entity.position;
    const hitRange=Math.max(2.5,Math.min(3.5,Number(cfg.attackRange ?? 3.2)));
    const detectionRange=Math.max(5,Math.min(20,Number(cfg.detectionRange ?? 15)));
    const [dx,dz]=directions[direction]||directions.EAST;
    // Never chase sideways or behind the bot; always resume walking on the fixed course.
    const candidates=Object.values(bot.entities).filter(e=>{
      if(!isHostile(e))return false;
      const offX=e.position.x-pos.x,offZ=e.position.z-pos.z;
      const ahead=offX*dx+offZ*dz;
      const sideways=Math.abs(offX*dz-offZ*dx);
      return ahead>=-2.5 && ahead<=detectionRange && sideways<=3.0;
    }).sort((a,b)=>a.position.distanceTo(pos)-b.position.distanceTo(pos));
    const target=candidates[0];
    if(target && now-lastTargetLog>4000){console.log('[BOT] Cel:',target.name,'odleglosc',target.position.distanceTo(pos).toFixed(1)); lastTargetLog=now;}
    bot.setControlState('sprint',false);
    bot.setControlState('jump',false);
    if(target && target.position.distanceTo(pos)<=hitRange){
      bot.setControlState('forward',false);
      const offset=target.position.minus(pos);
      const horizontal=Math.hypot(offset.x,offset.z);
      const yaw=Math.atan2(-offset.x,offset.z);
      const pitch=Math.atan2((pos.y+1.62)-(target.position.y+(target.height||1.6)*0.55),Math.max(horizontal,0.01));
      bot.look(yaw,pitch,true).catch(()=>{});
      if(now-lastHit>=Math.max(550,Number(cfg.attackCooldownMs??700))){
        lastHit=now;
        try {bot.attack(target);if(now-lastTargetLog>1500){console.log('[BOT] Atakuje grupe mobow przy',target.name);lastTargetLog=now;}}
        catch(e){console.warn('[BOT] Blad ataku:',e.message)}
      }
      return;
    }
    // Continue toward enemies; do not rotate into them and leave the lane.
    bot.look(Math.atan2(-dx,dz),0,true).catch(()=>{});
    const duty=Math.max(0.35,Math.min(1,Number(cfg.walkDutyCycle??1)));
    bot.setControlState('forward',now%1000<duty*1000);
  }
  bot.on('spawn',()=>{
    started=true;running=false;stopMovement();
    console.log('[BOT] Jestem w Minecraft! Czekam na /rodeolive start.');
    if(walkTimer)clearInterval(walkTimer);if(statusTimer)clearInterval(statusTimer);currentTargetId=null;
    walkTimer=setInterval(move,100);
    statusTimer=setInterval(status,1200);
    status();
  });
  bot.on('death',()=>{
    started=false;running=false;currentTargetId=null;stopMovement();
    console.log('[BOT] Zginalem. Respawn za 2 sekundy.');
    clearTimeout(respawnTimer);
    respawnTimer=setTimeout(()=>{if(alive){try{bot.respawn()}catch(e){console.warn('[BOT] Respawn:',e.message)}}},2000);
  });
  bot.on('kicked',reason=>console.error('[BOT] Kick:',typeof reason==='string'?reason:JSON.stringify(reason)));
  bot.on('error',err=>console.error('[BOT] Blad:',err.message));
  bot.on('end',()=>{
    alive=false;started=false;running=false;stopMovement();stopIntervals();clearTimeout(respawnTimer);
    console.warn('[BOT] Rozlaczono. Proba ponownego polaczenia.');
    clearTimeout(reconnectTimer);
    reconnectTimer=setTimeout(connect,Math.max(5,cfg.reconnectSeconds||10)*1000);
  });
}
connect();
