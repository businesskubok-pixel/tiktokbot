'use strict';
const fs = require('fs');
const mineflayer = require('mineflayer');
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder');
const config = JSON.parse(fs.readFileSync('./config.json', 'utf8'));
let current = null, restart = null;
const directions = { east:[1,0], west:[-1,0], south:[0,1], north:[0,-1] };
const [dx,dz] = directions[config.direction] || directions.east;
function connect() {
  let alive = true;
  const bot = mineflayer.createBot({ host:config.host, port:config.port || 25565,
    username:config.username || 'RodeoBot', auth:config.auth || 'offline', version:config.version || '1.20.6', respawn:false });
  current = bot;
  bot.loadPlugin(pathfinder);
  let loop = null, walking = false, stopped = false;
  function schedule() { if (loop) clearInterval(loop); loop = setInterval(advance, 1800); }
  function advance() {
    if (!bot.entity || stopped || walking) return;
    let p = bot.entity.position;
    const segment = Math.max(5,Math.min(30,config.walkSegment || 15));
    let goal = new goals.GoalNear(Math.floor(p.x + dx*segment),Math.floor(p.y),Math.floor(p.z + dz*segment),2);
    walking = true;
    bot.pathfinder.goto(goal).catch(err => console.warn('[BOT] Trasa wymaga korekty:',err.message)).finally(()=>{ walking=false; });
  }
  bot.on('spawn', () => {
    console.log('[BOT] Pojawil sie w swiecie, ruszam.');
    stopped=false; walking=false;
    const moves = new Movements(bot);
    moves.canDig = false;
    moves.allow1by1towers = false;
    moves.allowFreeMotion = false;
    bot.pathfinder.setMovements(moves);
    schedule(); advance();
  });
  bot.on('death', () => {
    console.log('[BOT] Smierc. Automatyczny respawn za 2 sekundy.');
    stopped=true; walking=false;
    bot.pathfinder.setGoal(null);
    if (loop) clearInterval(loop);
    setTimeout(() => { if (alive) { try { bot.respawn(); } catch(e) { console.warn('[BOT] Respawn:', e.message); } } },2000);
  });
  bot.on('kicked', reason => console.error('[BOT] Kick:',reason));
  bot.on('error', err=>console.error('[BOT] Blad:',err.message));
  bot.on('end', () => {
    alive=false; stopped=true;
    if(loop) clearInterval(loop);
    console.warn('[BOT] Rozlaczono. Kolejna proba za kilka sekund.');
    if (restart) clearTimeout(restart);
    restart=setTimeout(connect,Math.max(5,config.reconnectSeconds || 10)*1000);
  });
}
connect();
