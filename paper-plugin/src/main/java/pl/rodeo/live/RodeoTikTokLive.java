package pl.rodeo.live;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/** Rodeo TikTok LIVE 1.3.2. All Bukkit world/entity operations occur on the primary thread. */
public final class RodeoTikTokLive extends JavaPlugin implements Listener, CommandExecutor {
    private boolean running;
    private Location roundStart;
    private int deaths;
    private long invulnerableUntil;
    private HttpServer server;
    private BukkitTask task;
    private final Deque<Column> pending = new ArrayDeque<>();
    private final Set<Long> pendingKeys = new HashSet<>();
    private final Set<UUID> giftMobs = new HashSet<>();
    private boolean creatingGiftMob = false;
    private boolean dealingSweepDamage = false;
    private final Deque<GiftOrder> giftQueue = new ArrayDeque<>();
    private record GiftOrder(EntityType type, String event, String nick, int remaining) {}
    private long lastLaneCorrection = 0;
    // Prefetch only a small number of chunks along the lane; never generate chunks synchronously every tick.
    private final Set<Long> prefetchingChunks = new HashSet<>();
    // Short rolling window of plugin tickets: keep corridor chunks available to
    // both the bot and the moving spectator, without retaining the whole world.
    private final Set<Long> pinnedChunks = new HashSet<>();
    private int lastPrefetchChunkX = Integer.MIN_VALUE;
    private int lastPrefetchChunkZ = Integer.MIN_VALUE;
    private final Map<UUID, CameraSession> cameras = new HashMap<>();
    private static final class CameraSession {
        final GameMode previousMode;
        final Location previousLocation;
        long lastCorrectionTick;
        CameraSession(GameMode mode, Location pos) {
            previousMode = mode; previousLocation = pos;
            lastCorrectionTick = Bukkit.getCurrentTick();
        }
        GameMode previousMode() { return previousMode; }
        Location previousLocation() { return previousLocation; }
    }
    private record Column(int x, int z, int floorY) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("rodeolive")).setExecutor(this);
        running = getConfig().getBoolean("start-enabled", false);
        startBridge();
        Bukkit.getScheduler().runTaskLater(this, this::removeOtherMobs, 30L);
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("RodeoTikTokLive 1.3.2. Komenda /rodeolive debug pokazuje swiat i nick bota.");
    }
    @Override public void onDisable() {
        if (task != null) task.cancel();
        for (UUID id : new ArrayList<>(cameras.keySet())) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null && viewer.isOnline()) stopCamera(viewer, false);
            else cameras.remove(id);
        }
        if (server != null) server.stop(0);
        releaseChunkTickets();
        pending.clear(); pendingKeys.clear(); prefetchingChunks.clear(); giftMobs.clear(); giftQueue.clear();
    }
    private World liveWorld() { return Bukkit.getWorld(getConfig().getString("world", "world")); }
    private String botName() { return getConfig().getString("bot-name", "RodeoBot"); }
    private Player bot() {
        Player p = Bukkit.getPlayerExact(botName());
        if (p != null) return p;
        for (Player candidate : Bukkit.getOnlinePlayers()) if (candidate.getName().equalsIgnoreCase(botName())) return candidate;
        return null;
    }
    private boolean valid(Player p) { return p != null && p.isOnline() && liveWorld() != null && p.getWorld().equals(liveWorld()); }
    private int dirX() { return switch (getConfig().getString("lane.direction", "EAST").toUpperCase(Locale.ROOT)) {case "WEST" -> -1; case "EAST" -> 1; default -> 0;}; }
    private int dirZ() { return switch (getConfig().getString("lane.direction", "EAST").toUpperCase(Locale.ROOT)) {case "NORTH" -> -1; case "SOUTH" -> 1; default -> 0;}; }
    private long progress(int x,int z) { return (long)x*dirX()+(long)z*dirZ(); }
    private long colKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private void clearPending() { pending.clear();pendingKeys.clear(); }

    private void equip(Player p) {
        if (!getConfig().getBoolean("bot.equipment-enabled",true)) return;
        ItemStack helmet = new ItemStack(Material.NETHERITE_HELMET);
        ItemStack chest = new ItemStack(Material.NETHERITE_CHESTPLATE);
        ItemStack legs = new ItemStack(Material.NETHERITE_LEGGINGS);
        ItemStack boots = new ItemStack(Material.NETHERITE_BOOTS);
        for (ItemStack armor : List.of(helmet,chest,legs,boots)) {
            armor.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
            armor.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        }
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, getConfig().getInt("bot.sword-sharpness",5));
        sword.addUnsafeEnchantment(Enchantment.UNBREAKING,3);
        p.getInventory().setHeldItemSlot(0);
        p.getInventory().setHelmet(helmet);p.getInventory().setChestplate(chest);
        p.getInventory().setLeggings(legs);p.getInventory().setBoots(boots);
        p.getInventory().setItemInMainHand(sword);
    }
    private void strengthen(Player p) {
        if (!valid(p)) return;
        var attr=p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max=Math.max(20,Math.min(200,getConfig().getDouble("bot.max-health",60)));
        if (attr!=null) attr.setBaseValue(max);
        p.setHealth(Math.min(max,p.getHealth()<=0?max:p.getHealth()));
        p.setFoodLevel(20);p.setSaturation(20);
        // Vanilla player walk speed is 0.2; lower it for a relaxed LIVE pace.
        float walkSpeed=(float)Math.max(0.04,Math.min(0.2,getConfig().getDouble("bot.walk-speed",0.12)));
        p.setWalkSpeed(walkSpeed);
        equip(p);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer(); if(!p.getName().equalsIgnoreCase(botName())) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            if (!valid(p) && liveWorld()!=null && getConfig().getBoolean("bot.auto-teleport-to-world",true)) {
                p.teleport(liveWorld().getSpawnLocation().clone().add(0.5,1,0.5));
            }
            if (valid(p)) {strengthen(p); invulnerableUntil=System.currentTimeMillis()+8000L; }
        },10L);
    }
    @EventHandler public void onDamage(EntityDamageEvent e) {
        if(!(e.getEntity() instanceof Player p) || !valid(p) || !p.getName().equalsIgnoreCase(botName())) return;
        if(System.currentTimeMillis()<invulnerableUntil){e.setCancelled(true);return;}
        e.setDamage(e.getDamage()*Math.max(0.05,Math.min(1,getConfig().getDouble("bot.damage-multiplier",0.5))));
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p=e.getEntity();if(!p.getName().equalsIgnoreCase(botName()))return;
        deaths++;giftQueue.clear();e.setKeepInventory(true);e.getDrops().clear();e.setDroppedExp(0);
        Bukkit.broadcastMessage(ChatColor.GOLD+"[Rodeo LIVE] Bot zginal - runda "+(deaths+1)+" rozpocznie sie po respawnie.");
        clearPending();
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        if(!e.getPlayer().getName().equalsIgnoreCase(botName()))return;
        if(roundStart!=null && roundStart.getWorld()!=null) e.setRespawnLocation(roundStart.clone());
        else if(liveWorld()!=null)e.setRespawnLocation(liveWorld().getSpawnLocation().clone().add(0.5,1,0.5));
        Bukkit.getScheduler().runTaskLater(this,()->{
            Player p=e.getPlayer();if(valid(p)){strengthen(p);p.setHealth(p.getMaxHealth());invulnerableUntil=System.currentTimeMillis()+8000L;}
            clearPending();
        },5L);
    }
    // Third-person follow camera: teleport the ACTUAL spectator player instead of
    // attaching its viewpoint to a moving ArmorStand. The server then streams
    // chunks around the viewer's real position as the bot advances.
    // The bot remains a normal Mineflayer player; the camera is a separate viewer.
    private Location cameraLocation(Player target) {
        Location botPos = target.getLocation();
        int dx = dirX(), dz = dirZ();
        if (dx == 0 && dz == 0) { dx = 1; dz = 0; }
        double distance = Math.max(2.5, Math.min(9.0, getConfig().getDouble("camera.distance", 5.5)));
        double height = Math.max(0.8, Math.min(5.0, getConfig().getDouble("camera.height", 2.4)));
        Location view = botPos.clone().add(-dx * distance, height, -dz * distance);
        org.bukkit.util.Vector lookAt = botPos.clone().add(0, 1.1, 0).toVector();
        org.bukkit.util.Vector dir = lookAt.subtract(view.toVector());
        if (dir.lengthSquared() > 0.001) view.setDirection(dir);
        return view;
    }
    private void camera(Player viewer) {
        Player target = bot();
        if (!valid(target)) { viewer.sendMessage(ChatColor.RED + "RodeoBot nie jest online w swiecie LIVE."); return; }
        if (viewer.equals(target)) { viewer.sendMessage(ChatColor.RED + "Nie mozna obserwowac samego siebie."); return; }
        if (cameras.containsKey(viewer.getUniqueId())) { viewer.sendMessage(ChatColor.YELLOW + "Kamera juz dziala. /rodeolive uncamera aby wrocic."); return; }
        CameraSession session = new CameraSession(viewer.getGameMode(), viewer.getLocation().clone());
        cameras.put(viewer.getUniqueId(), session);
        viewer.setGameMode(GameMode.SPECTATOR);
        // Do not call setSpectatorTarget: it can leave the viewer's server-side
        // chunk subscription at its old position, causing the sky/void bug.
        viewer.teleport(cameraLocation(target));
        viewer.sendMessage(ChatColor.GREEN + "Kamera zza bota aktywna! /rodeolive uncamera - powrot.");
    }
    // Smooth spectator follow. The client receives velocity updates instead of
    // repeated teleport packets. Teleport is reserved for large desyncs only.
    // IMPORTANT: spectator movement can vary with the client; the fallback
    // correction is intentionally throttled to avoid the 1.3.1 camera jitter.
    private static float blendAngle(float current, float target, double factor) {
        float delta = (target - current) % 360f;
        if (delta > 180f) delta -= 360f;
        if (delta < -180f) delta += 360f;
        return current + (float)(delta * factor);
    }

    private void updateCameras() {
        if (cameras.isEmpty()) return;
        Player target = bot();
        if (!valid(target)) {
            for (UUID id : new ArrayList<>(cameras.keySet())) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null && viewer.isOnline()) stopCamera(viewer, true);
                else cameras.remove(id);
            }
            return;
        }

        Location desired = cameraLocation(target);
        World world = desired.getWorld();
        if (world == null || !world.isChunkLoaded(desired.getBlockX() >> 4, desired.getBlockZ() >> 4)) return;

        boolean smoothFollow = getConfig().getBoolean("camera.smooth-follow", true);
        double smoothing = Math.max(0.05, Math.min(0.8, getConfig().getDouble("camera.smoothing", 0.30)));
        double maxSpeed = Math.max(0.10, Math.min(1.5, getConfig().getDouble("camera.max-speed-per-tick", 0.65)));
        double maxError = Math.max(2.0, Math.min(20.0, getConfig().getDouble("camera.max-distance-error", 5.0)));
        int correctionTicks = Math.max(10, Math.min(100, getConfig().getInt("camera.correction-interval-ticks", 20)));
        long tick = Bukkit.getCurrentTick();

        for (UUID id : new ArrayList<>(cameras.keySet())) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null || !viewer.isOnline()) { cameras.remove(id); continue; }
            if (viewer.getGameMode() != GameMode.SPECTATOR) { stopCamera(viewer, false); continue; }
            Location current = viewer.getLocation();
            if (!smoothFollow) {
                if (tick % 2 == 0) viewer.teleport(desired);
                continue;
            }
            if (!current.getWorld().equals(world)) {
                viewer.teleport(desired);
                continue;
            }

            org.bukkit.util.Vector error = desired.toVector().subtract(current.toVector());
            double distanceSquared = error.lengthSquared();
            CameraSession session = cameras.get(id);
            if (session == null) continue;

            // Large drift (e.g. after bot respawn): occasional hard recovery.
            // This also prevents leaving the camera far from the chunk window.
            if (distanceSquared > maxError * maxError && tick - session.lastCorrectionTick >= correctionTicks) {
                if (viewer.teleport(desired)) session.lastCorrectionTick = tick;
                continue;
            }

            org.bukkit.util.Vector velocity = error.multiply(smoothing);
            if (velocity.lengthSquared() > maxSpeed * maxSpeed) velocity.normalize().multiply(maxSpeed);
            if (distanceSquared < 0.025) velocity.zero();
            viewer.setVelocity(velocity);

            float yaw = blendAngle(current.getYaw(), desired.getYaw(), Math.min(0.65, smoothing * 1.5));
            float pitch = blendAngle(current.getPitch(), desired.getPitch(), Math.min(0.65, smoothing * 1.5));
            viewer.setRotation(yaw, pitch);
        }
    }
    private void stopCamera(Player viewer, boolean notify) {
        CameraSession session = cameras.remove(viewer.getUniqueId());
        if (session == null) { if(notify) viewer.sendMessage("Kamera nie jest aktywna."); return; }
        viewer.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        viewer.setGameMode(session.previousMode());
        if (session.previousLocation().getWorld() != null) viewer.teleport(session.previousLocation());
        if (notify) viewer.sendMessage(ChatColor.GREEN + "Widok kamery zakonczony. Wrociles do swojej postaci.");
    }
    private void uncamera(Player viewer) { stopCamera(viewer, true); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { cameras.remove(e.getPlayer().getUniqueId()); }

    // Only mobs intentionally created by RodeoTikTokLive are allowed on the LIVE world.
    // Do not cancel player spawns, spectator cameras, items or other non-creature events.
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=false)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (!getConfig().getBoolean("mobs.only-gift-mobs", true)) return;
        if (liveWorld()==null || !e.getLocation().getWorld().equals(liveWorld())) return;
        if (!creatingGiftMob) e.setCancelled(true);
    }

    // Creepers still explode and may hurt/knock back the bot, but terrain is never damaged.
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=false)
    public void onCreeperExplosion(EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof Creeper) || liveWorld()==null || !e.getLocation().getWorld().equals(liveWorld())) return;
        if (!getConfig().getBoolean("mobs.creeper-terrain-protection", true)) return;
        e.blockList().clear();
        e.setYield(0);
    }

    private void removeOtherMobs() {
        if (!getConfig().getBoolean("mobs.only-gift-mobs", true) || !getConfig().getBoolean("mobs.remove-existing-mobs", true)) return;
        World w=liveWorld();if(w==null)return;
        for (Entity e : w.getEntities()) {
            if (e instanceof Mob && !giftMobs.contains(e.getUniqueId())) e.remove();
            // Animals are creatures too; remove them only on the dedicated LIVE world.
            else if (e instanceof Animals && !giftMobs.contains(e.getUniqueId())) e.remove();
        }
    }

    // Explosions and combat may push the bot outside of the fixed corridor.
    // Bring it back to the centre line after a short grace period; preserve progress.
    private void correctBotLane(Player p) {
        if (roundStart==null || !getConfig().getBoolean("lane.recenter-bot", true)) return;
        int dx=dirX(),dz=dirZ();if(dx==0 && dz==0)return;
        double lateral=dx!=0 ? p.getLocation().getZ()-roundStart.getZ() : p.getLocation().getX()-roundStart.getX();
        double maxDeviation=Math.max(1.5,getConfig().getDouble("lane.max-deviation", 2.5));
        if (Math.abs(lateral)<maxDeviation || System.currentTimeMillis()-lastLaneCorrection<1200L) return;
        // Recover only if the bot is already outside the path, to keep ordinary fights natural.
        Location loc=p.getLocation().clone();
        if (dx!=0) loc.setZ(roundStart.getZ()); else loc.setX(roundStart.getX());
        loc.setY(roundStart.getBlockY());
        int x=loc.getBlockX(),z=loc.getBlockZ();
        if(!p.getWorld().isChunkLoaded(x>>4,z>>4)) return;
        prepareColumn(p.getWorld(),new Column(x,z,roundStart.getBlockY()-1),32);
        p.teleport(loc);
        p.setVelocity(new org.bukkit.util.Vector(0,0,0));
        lastLaneCorrection=System.currentTimeMillis();
    }
    private void tick() {
        updateCameras();
        // One check per second: fill freed slots with pending TikTok gift mobs.
        if (Bukkit.getCurrentTick()%20==0 && running) drainGiftQueue();
        Player p=bot();if(!running||!valid(p)||p.isDead())return;
        if(p.getTicksLived()% (20*Math.max(1,getConfig().getInt("bot.regen-every-seconds",4)))==0){
            p.setHealth(Math.min(p.getMaxHealth(),p.getHealth()+getConfig().getDouble("bot.regen-health",2)));
            p.setFoodLevel(20);
        }
        if(!getConfig().getBoolean("lane.enabled",true) || roundStart==null)return;
        correctBotLane(p);
        if(Bukkit.getCurrentTick()%20==0) prefetchLane(p);
        queueLaneAhead(p);
        int budget=Math.max(1,Math.min(500,getConfig().getInt("lane.max-block-changes-per-tick",160)));
        int changes=0;
        // One column = width strip + air clearance (bounded); no world-wide scanning.
        while(!pending.isEmpty() && changes<budget){
            Column c=pending.pollFirst(); pendingKeys.remove(colKey(c.x,c.z));
            changes+=prepareColumn(p.getWorld(),c,budget-changes);
        }
        // Keep pace controlled to avoid queues growing without bound.
    }
    private void releaseChunkTickets() {
        World w = liveWorld();
        if (w != null) for (long key : new HashSet<>(pinnedChunks))
            w.removePluginChunkTicket((int)(key >> 32), (int)key, this);
        pinnedChunks.clear();
    }
    // Maintain a small sliding window of chunk tickets around and ahead of the
    // bot. Loaded chunks aren't immediately released when the bot approaches a
    // new region, so the camera and lane editor can catch up without sky/void.
    private void prefetchLane(Player p) {
        World w = p.getWorld(); int dx = dirX(), dz = dirZ();
        if (dx == 0 && dz == 0) return;
        int distance = Math.max(32, Math.min(96, getConfig().getInt("lane.chunk-prefetch-blocks", 56)));
        int x = p.getLocation().getBlockX(), z = p.getLocation().getBlockZ();
        Set<Long> wanted = new LinkedHashSet<>();
        // Include camera behind the bot and every chunk ahead along the path.
        for (int offset = -16; offset <= distance; offset += 8) {
            int cx = (x + offset * dx) >> 4, cz = (z + offset * dz) >> 4;
            wanted.add(colKey(cx, cz));
        }
        for (long key : new HashSet<>(pinnedChunks)) {
            if (!wanted.contains(key)) {
                w.removePluginChunkTicket((int)(key >> 32), (int)key, this);
                pinnedChunks.remove(key);
            }
        }
        int requested = 0;
        for (long key : wanted) {
            if (pinnedChunks.contains(key) || prefetchingChunks.contains(key)) continue;
            int cx = (int)(key >> 32), cz = (int)key;
            if (w.isChunkLoaded(cx, cz)) {
                if (w.addPluginChunkTicket(cx, cz, this)) pinnedChunks.add(key);
                continue;
            }
            if (requested >= 2 || prefetchingChunks.size() >= 4) break;
            prefetchingChunks.add(key);
            requested++;
            w.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> {
                // Paper's future callback is not guaranteed to be on the main thread.
                if (!isEnabled()) return;
                Bukkit.getScheduler().runTask(this, () -> {
                    prefetchingChunks.remove(key);
                    if (error != null) {
                        getLogger().warning("Chunk LIVE " + cx + "," + cz + ": " + error.getMessage());
                    } else if (w.isChunkLoaded(cx, cz) && !pinnedChunks.contains(key)) {
                        // Ticket will be dropped if it is no longer needed on next pass.
                        if (w.addPluginChunkTicket(cx, cz, this)) pinnedChunks.add(key);
                    }
                });
            });
        }
    }
    private void queueLaneAhead(Player p) {
        int dx=dirX(),dz=dirZ(); if(dx==0&&dz==0)return;
        int look=Math.max(8,Math.min(32,getConfig().getInt("lane.look-ahead",18)));
        int x=p.getLocation().getBlockX(),z=p.getLocation().getBlockZ();
        long position=progress(x,z);
        // Queue nearby columns every tick, prioritizing closest first; do not repeatedly enqueue.
        int floorY=roundStart.getBlockY()-1;
        int width=Math.max(1,Math.min(9,getConfig().getInt("lane.width",5)));
        int half=width/2;
        int centerX=roundStart.getBlockX(),centerZ=roundStart.getBlockZ();
        for(int a=0;a<=look;a++) {
            int sx=x+a*dx,sz=z+a*dz;
            for(int lateral=-half;lateral<=half;lateral++) {
                int tx=dx==0?centerX+lateral:sx;
                int tz=dz==0?centerZ+lateral:sz;
                // Keep a straight line, but if bot got pushed sideways, it can re-enter lane.
                if(pending.size()>300) return;
                long key=colKey(tx,tz);
                if(pendingKeys.add(key)) pending.addLast(new Column(tx,tz,floorY));
            }
        }
    }
    private int prepareColumn(World world,Column c,int budget) {
        if(budget<1)return 0;
        int x=c.x,z=c.z,y=c.floorY;
        if(y<=world.getMinHeight()+2||y>=world.getMaxHeight()-8)return 0;
        if(!world.isChunkLoaded(x>>4,z>>4))return 0;
        // Prevent terrain edits above/below a tight height band to keep server responsive.
        int removed=0;
        Block floor=world.getBlockAt(x,y,z);
        if(floor.getType()!=Material.GRASS_BLOCK && removed<budget){floor.setType(Material.GRASS_BLOCK,false);removed++;}
        int airHeight=Math.max(3,Math.min(8,getConfig().getInt("lane.clear-height",5)));
        for(int dy=1;dy<=airHeight && removed<budget;dy++){
            Block b=world.getBlockAt(x,y+dy,z);
            if(!b.getType().isAir()){b.setType(Material.AIR,false);removed++;}
        }
        // Some trees / tall structures would remain above clear-height: intentional bounded clearing.
        return removed;
    }
    // Attack only TikTok-created opponents; sweep deals reduced damage to other nearby gift mobs.
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onBotAttack(EntityDamageByEntityEvent e) {
        if (dealingSweepDamage || !(e.getDamager() instanceof Player p) || !valid(p) || !p.getName().equalsIgnoreCase(botName())) return;
        if (!(e.getEntity() instanceof Mob primary) || !giftMobs.contains(primary.getUniqueId())) return;
        double radius=Math.max(1,Math.min(4,getConfig().getDouble("bot.sweep-radius",2.8)));
        int extra=Math.max(0,Math.min(8,getConfig().getInt("bot.sweep-extra-targets",4)));
        if(extra==0)return;
        dealingSweepDamage=true;
        try {
            int hit=0;
            for(Entity other:primary.getNearbyEntities(radius,radius,radius)) {
                if(hit>=extra)break;
                if(!(other instanceof Mob mob) || mob.isDead() || !giftMobs.contains(mob.getUniqueId()))continue;
                if(!mob.getWorld().equals(primary.getWorld()) || mob.getLocation().distanceSquared(p.getLocation())>16)continue;
                mob.damage(Math.max(1,e.getFinalDamage()*0.55),p);
                hit++;
            }
        } finally {dealingSweepDamage=false;}
    }

    /** Each gift can map to one mob type OR to a mixed wave (e.g. Warden + Zombies). */
    private int queueGift(String event,String nick) {
        if(!running || !valid(bot()))return 0;
        String key="mobs.events."+event.toLowerCase(Locale.ROOT)+".";
        List<GiftOrder> orders=new ArrayList<>();
        List<Map<?,?>> wave=new ArrayList<>(getConfig().getMapList(key+"mobs"));
        if(!wave.isEmpty()) {
            for(Map<?,?> entry:wave) {
                try {
                    EntityType type=EntityType.valueOf(String.valueOf(entry.get("type")).toUpperCase(Locale.ROOT));
                    int count=Integer.parseInt(String.valueOf(entry.get("count")));
                    if(!type.isAlive() || !type.isSpawnable() || count<1 || count>30)throw new IllegalArgumentException("invalid mob/count");
                    orders.add(new GiftOrder(type,event,nick,count));
                }catch(Exception ex){getLogger().warning("Niepoprawna fala dla "+event+": "+ex.getMessage());return 0;}
            }
        } else {
            try {
                EntityType type=EntityType.valueOf(getConfig().getString(key+"type","").toUpperCase(Locale.ROOT));
                if(!type.isAlive()||!type.isSpawnable())return 0;
                int count=Math.max(1,Math.min(30,getConfig().getInt(key+"count",1)));
                orders.add(new GiftOrder(type,event,nick,count));
            }catch(Exception ex){getLogger().warning("Nieznane zdarzenie/mob: "+event);return 0;}
        }
        int total=orders.stream().mapToInt(GiftOrder::remaining).sum();
        int queueLimit=Math.max(30,Math.min(1000,getConfig().getInt("mobs.max-pending-mobs",150)));
        int pendingTotal=giftQueue.stream().mapToInt(GiftOrder::remaining).sum();
        // Do not enqueue half a purchased wave: accept all 11 or reject it as a whole.
        if(total>queueLimit-pendingTotal){getLogger().warning("Pelna kolejka mobow: odrzucono event "+event);return 0;}
        giftQueue.addAll(orders);
        drainGiftQueue();
        return total;
    }

    private void drainGiftQueue() {
        if(!running||!valid(bot()))return;
        giftMobs.removeIf(id->{Entity entity=Bukkit.getEntity(id);return entity==null||entity.isDead();});
        int limit=Math.max(1,Math.min(100,getConfig().getInt("mobs.max-live-mobs",30)));
        int attempts=0;
        while(!giftQueue.isEmpty()&&giftMobs.size()<limit&&attempts++<10) {
            GiftOrder order=giftQueue.removeFirst();
            // Do not exceed capacity; spawnGift itself also checks the live mob cap.
            int spawned=spawnGift(order);
            if(spawned==0){giftQueue.addFirst(order);break;}
            int left=order.remaining()-spawned;
            if(left>0)giftQueue.addFirst(new GiftOrder(order.type(),order.event(),order.nick(),left));
        }
    }

    /** Spawn gift mobs onto a safe short platform ahead of the bot; return actual count. */
    private int spawnGift(GiftOrder order) {
        Player p=bot();
        if(!running||!valid(p)||p.isDead())return 0;
        String event=order.event(),username=order.nick();
        EntityType type=order.type();
        giftMobs.removeIf(id->Bukkit.getEntity(id)==null);
        int limit=Math.max(1,getConfig().getInt("mobs.max-live-mobs",30));
        int count=1; // Spawn 1 per available slot to guarantee cap and fair scheduling.
        String nick=username.replaceAll("[\\p{Cntrl}§]", "").trim();
        if(nick.isEmpty())nick="Widz";
        if(nick.length()>22)nick=nick.substring(0,22);
        int dx=dirX(),dz=dirZ();
        int ahead=Math.max(3,Math.min(15,getConfig().getInt("mobs.spawn-distance-ahead",8)));
        int spawned=0;
        int floorY=(roundStart!=null?roundStart.getBlockY()-1:p.getLocation().getBlockY()-1);
        World w=p.getWorld();
        for(int i=0;i<count && giftMobs.size()<limit;i++) {
            // Keep the mobs in the same corridor as the bot and away from its current block.
            int distance=ahead+(i%3);
            int lateral=(i%3)-1;
            int x=p.getLocation().getBlockX()+dx*distance+(dz!=0?lateral:0);
            int z=p.getLocation().getBlockZ()+dz*distance+(dx!=0?lateral:0);
            if(!w.isChunkLoaded(x>>4,z>>4))continue;
            if(floorY<=w.getMinHeight()||floorY+3>=w.getMaxHeight())continue;
            // The corridor may not have been prepared yet: prepare this small area now.
            for(int xx=x-1;xx<=x+1;xx++)for(int zz=z-1;zz<=z+1;zz++){
                if(!w.isChunkLoaded(xx>>4,zz>>4))continue;
                Block floor=w.getBlockAt(xx,floorY,zz);
                if(!floor.getType().isSolid())floor.setType(Material.GRASS_BLOCK,false);
                for(int yy=floorY+1;yy<=floorY+3;yy++){
                    Block b=w.getBlockAt(xx,yy,zz);
                    if(!b.getType().isAir())b.setType(Material.AIR,false);
                }
            }
            Location loc=new Location(w,x+0.5,floorY+1,z+0.5);
            Entity mob;
            try {
                creatingGiftMob=true;
                mob=w.spawnEntity(loc,type);
            } catch(Exception ex){
                getLogger().warning("Nie mozna przywolac "+type+": "+ex.getMessage());continue;
            } finally {creatingGiftMob=false;}
            mob.setCustomName(ChatColor.YELLOW+nick+ChatColor.RED+" | "+event);
            mob.setCustomNameVisible(true);
            if(mob instanceof Mob attacker)attacker.setTarget(p);
            UUID id=mob.getUniqueId();giftMobs.add(id);spawned++;
            Bukkit.getScheduler().runTaskLater(this,()->{
                Entity e=Bukkit.getEntity(id);if(e!=null)e.remove();giftMobs.remove(id);
            },Math.max(10,getConfig().getInt("mobs.lifetime-seconds",90))*20L);
        }
        getLogger().info("LIVE event="+event+" nick="+nick+" mobs="+spawned+"/"+count);
        return spawned;
    }
    private void send(HttpExchange ex,int status,String message)throws IOException {
        byte[] bytes=message.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        ex.sendResponseHeaders(status,bytes.length);
        try(OutputStream os=ex.getResponseBody()){os.write(bytes);}
    }
    private void startBridge() {
        if(!getConfig().getBoolean("bridge.enabled",true))return;
        String token=getConfig().getString("bridge.token","");
        if(token.length()<20||token.startsWith("CHANGE_ME")){getLogger().warning("Most wylaczony: ustaw losowy bridge.token (20+ znakow).");return;}
        try{
            String bind=getConfig().getString("bridge.bind","127.0.0.1");
            if(!bind.equals("127.0.0.1")&&!bind.equals("::1")){getLogger().warning("Bridge dopuszcza jedynie localhost.");return;}
            server=HttpServer.create(new InetSocketAddress(bind,getConfig().getInt("bridge.port",18765)),16);
            server.createContext("/status",ex->{
                if(!token.equals(ex.getRequestHeaders().getFirst("X-Rodeo-Token"))){send(ex,403,"{\"error\":\"forbidden\"}");return;}
                // Bukkit state must be read on main thread, never inside HttpServer thread.
                Bukkit.getScheduler().runTask(this,()->{
                    try{send(ex,200,"{\"running\":"+running+",\"world\":\""+getConfig().getString("world","world")+"\",\"direction\":\""+getConfig().getString("lane.direction","EAST")+"\"}");}
                    catch(IOException ignored){}
                });
            });
            server.createContext("/gift",ex->{
                if(!ex.getRequestMethod().equals("POST")||!token.equals(ex.getRequestHeaders().getFirst("X-Rodeo-Token"))){send(ex,403,"{\"error\":\"forbidden\"}");return;}
                byte[] body=ex.getRequestBody().readNBytes(256);
                String[] parts=new String(body,StandardCharsets.UTF_8).trim().split("\\|",2);
                if(parts.length!=2||!parts[0].matches("[a-zA-Z0-9_]{1,32}")||parts[1].isBlank()||parts[1].length()>90){send(ex,400,"{\"error\":\"invalid payload\"}");return;}
                Bukkit.getScheduler().runTask(this,()->queueGift(parts[0],parts[1]));
                send(ex,202,"{\"queued\":true}");
            });
            server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"RodeoBridge");t.setDaemon(true);return t;}));
            server.start();getLogger().info("Bridge nasluchuje: "+bind+":"+server.getAddress().getPort());
        }catch(IOException e){getLogger().warning("Bridge nie wystartowal: "+e.getMessage());}
    }
    private String describeGift(String key) {
        List<Map<?,?>> wave=new ArrayList<>(getConfig().getMapList(key+"mobs"));
        if(!wave.isEmpty())return String.join(" + ",wave.stream().map(e -> String.valueOf(e.get("count"))+"x "+String.valueOf(e.get("type"))).toList());
        return getConfig().getInt(key+"count",1)+"x "+getConfig().getString(key+"type","?");
    }

    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if(!sender.hasPermission("rodeolive.admin"))return true;
        if(args.length==0){sender.sendMessage("/rodeolive start | stop | reset | camera | uncamera | gifts | test <event> <nick> | stats | debug | reload");return true;}
        switch(args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                Player p=bot();World world=liveWorld();
                if(world==null){sender.sendMessage(ChatColor.RED+"Swiat '"+getConfig().getString("world")+"' nie istnieje! Sprawdz /rodeolive debug");break;}
                if(p==null){sender.sendMessage(ChatColor.RED+"Nie ma online gracza '"+botName()+"'. Sprawdz /rodeolive debug");break;}
                if(!valid(p)){
                    if(getConfig().getBoolean("bot.auto-teleport-to-world",true))p.teleport(world.getSpawnLocation().clone().add(0.5,1,0.5));
                    if(!valid(p)){sender.sendMessage(ChatColor.RED+"Bot jest w "+p.getWorld().getName()+", oczekiwany: "+world.getName());break;}
                }
                running=true;
                giftQueue.clear();
                removeOtherMobs();
                // Save fixed start near spawn; this is also the elevation of the flattened corridor.
                roundStart=p.getLocation().clone();
                clearPending();strengthen(p);
                sender.sendMessage(ChatColor.GREEN+"LIVE start! Bot: "+p.getName()+", swiat: "+p.getWorld().getName()+", kierunek "+getConfig().getString("lane.direction","EAST"));
            }
            case "stop" -> {running=false;giftQueue.clear();clearPending();sender.sendMessage("LIVE zatrzymany.");}
            case "reset" -> {
                Player p=bot();if(!valid(p)||roundStart==null){sender.sendMessage("Najpierw uruchom LIVE.");break;}
                p.teleport(roundStart.clone());clearPending();sender.sendMessage("Bot cofniety do startu. Runda od nowa.");
            }
            case "test" -> {
                if(args.length<3){sender.sendMessage("/rodeolive test rose Nick");break;}
                int n=queueGift(args[1],String.join(" ",Arrays.copyOfRange(args,2,args.length)));
                sender.sendMessage("Test "+args[1]+": przyjeto "+n+" mobow (aktywnych max 30, nadmiar w kolejce). Sprawdz /rodeolive stats.");
                if(n==0) sender.sendMessage("Sprawdz LIVE start, limit mobow oraz nazwe zdarzenia w config.yml.");
            }
            case "camera" -> {
                if(!(sender instanceof Player viewer)){sender.sendMessage("Komenda /rodeolive camera tylko w grze.");break;}
                camera(viewer);
            }
            case "uncamera" -> {
                if(!(sender instanceof Player viewer)){sender.sendMessage("Komenda /rodeolive uncamera tylko w grze.");break;}
                uncamera(viewer);
            }
            case "gifts" -> {
                var section=getConfig().getConfigurationSection("mobs.events");
                if(section==null){sender.sendMessage("Brak mobs.events w config.yml");break;}
                sender.sendMessage(ChatColor.GOLD+"Zdarzenia LIVE (opis/cena to etykiety, nie oficjalne ceny TikTok):");
                for(String id:section.getKeys(false)) {
                    String key="mobs.events."+id+".";
                    sender.sendMessage(ChatColor.YELLOW+id+ChatColor.WHITE+" -> "+describeGift(key)+" | "+getConfig().getString(key+"description",id));
                }
            }
            case "stats" -> sender.sendMessage("LIVE="+running+", bot="+(valid(bot())?"online":"offline/wrong-world")+", deaths="+deaths+", mobs="+giftMobs.size()+", gift queue="+giftQueue.stream().mapToInt(GiftOrder::remaining).sum()+", lane queue="+pending.size());
            case "debug" -> {
                Player p=bot();sender.sendMessage("Bot szukany: '"+botName()+"', wynik: "+(p==null?"BRAK":p.getName()+" / swiat="+p.getWorld().getName()));
                sender.sendMessage("Swiat z configu: '"+getConfig().getString("world", "world")+"', istnieje="+(liveWorld()!=null));
                sender.sendMessage("Swiaty: "+String.join(", ",Bukkit.getWorlds().stream().map(World::getName).toList()));
            }
            case "reload" -> {
                reloadConfig();Player activeBot=bot();if(valid(activeBot))strengthen(activeBot);if(server!=null){server.stop(0);server=null;}startBridge();clearPending();sender.sendMessage("Config przeladowany.");
            }
            default -> sender.sendMessage("Nieznana komenda. /rodeolive");
        }
        return true;
    }
}
