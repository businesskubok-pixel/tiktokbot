'use strict';
const fs = require('fs');
const mineflayer = require('mineflayer');
const cfg = JSON.parse(fs.readFileSync('./config.json', 'utf8'));
const directions = { EAST:[1,0], WEST:[-1,0], SOUTH:[0,1], NORTH:[0,-1] };
let reconnectTimer;
function connect() {
  const bot = mineflayer.createBot({
    host:cfg.host || '127.0.0.1', port:cfg.port || 25565,
    username:cfg.username || 'RodeoBot', auth:cfg.auth || 'offline',
    version:cfg.version || '1.20.6', respawn:false
  });
  let alive=true, started=false, running=false, direction=(cfg.direction || 'EAST').toUpperCase();
  let walkTimer, combatTimer, statusTimer, respawnTimer, lastHit=0;
  function stopMovement(){ try {bot.setControlState('forward',false);bot.setControlState('sprint',false);bot.setControlState('jump',false);}catch{} }
  function stopIntervals(){for(const id of [walkTimer,combatTimer,statusTimer])if(id)clearInterval(id);walkTimer=combatTimer=statusTimer=null;}
  async function status(){
    if(!cfg.pluginStatusUrl || !cfg.token)return;
    try {
      const res=await fetch(cfg.pluginStatusUrl,{headers:{'X-Rodeo-Token':cfg.token},signal:AbortSignal.timeout(1800)});
      if(!res.ok)throw new Error('HTTP '+res.status);
      const value=await res.json();
      running=Boolean(value.running);
      if(value.direction && directions[value.direction.toUpperCase()])direction=value.direction.toUpperCase();
      if(!running)stopMovement();
    }catch(err){
      running=false;stopMovement();
      if(started)console.warn('[BOT] Nie moge sprawdzic stanu pluginu:',err.message);
    }
  }
  function move(){
    if(!started || !bot.entity || !alive)return;
    if(!running){stopMovement();return;}
    const [dx,dz]=directions[direction]||directions.EAST;
    // Heading Minecraft: 0 -> south; -PI/2 -> east.
    const yaw=Math.atan2(-dx,dz);
    bot.look(yaw,0,true).catch(()=>{});
    bot.setControlState('forward',true);
    bot.setControlState('sprint',Boolean(cfg.sprint));
    bot.setControlState('jump',false);
  }
  function fight(){
    if(!started || !alive || !running || !bot.entity)return;
    const types = new Set(['zombie','husk','drowned','skeleton','stray','creeper','spider','cave_spider']);
    const limit=Math.max(2,Math.min(4,cfg.attackRange||3.2));
    const target=bot.nearestEntity(e=>e.type==='mob' && types.has(e.name) && e.position && e.position.distanceTo(bot.entity.position)<limit);
    if(!target)return;
    if(Date.now()-lastHit<Math.max(500,cfg.attackCooldownMs||700))return;
    lastHit=Date.now();
    try {bot.attack(target);console.log('[BOT] Atak:',target.name);}catch(e){console.warn('[BOT] Atak:',e.message)}
  }
  bot.on('spawn',()=>{
    started=true;running=false;stopMovement();
    console.log('[BOT] Jestem w Minecraft! Czekam na /rodeolive start.');
    if(walkTimer)clearInterval(walkTimer);if(combatTimer)clearInterval(combatTimer);if(statusTimer)clearInterval(statusTimer);
    walkTimer=setInterval(move,300);
    combatTimer=setInterval(fight,250);
    statusTimer=setInterval(status,1200);
    status();
  });
  bot.on('death',()=>{
    started=false;running=false;stopMovement();
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
