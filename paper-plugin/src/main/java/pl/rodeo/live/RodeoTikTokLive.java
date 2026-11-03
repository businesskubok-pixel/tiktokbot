package pl.rodeo.live;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class RodeoTikTokLive extends JavaPlugin implements Listener, CommandExecutor {
    private boolean running;
    private Location start;
    private UUID botId;
    private long protectedUntil;
    private int deaths;
    private long previousSampleX = Long.MIN_VALUE;
    private long previousSampleZ = Long.MIN_VALUE;
    private final Deque<Location> trail = new ArrayDeque<>();
    private final Deque<BlockPos> blocksToRemove = new ArrayDeque<>();
    private final Set<UUID> giftMobs = new HashSet<>();
    private HttpServer bridge;
    private BukkitTask task;
    private record BlockPos(int x, int y, int z) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("rodeolive")).setExecutor(this);
        running = getConfig().getBoolean("start-enabled", false);
        startBridge();
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 1L);
        getLogger().info("RodeoTikTokLive aktywny. Uzyj /rodeolive start w swiecie LIVE.");
    }
    @Override public void onDisable() {
        if (task != null) task.cancel();
        if (bridge != null) bridge.stop(0);
        blocksToRemove.clear(); trail.clear(); giftMobs.clear();
    }
    private World liveWorld() { return Bukkit.getWorld(getConfig().getString("world", "tiktoklive")); }
    private Player bot() { return Bukkit.getPlayerExact(getConfig().getString("bot-name", "RodeoBot")); }
    private boolean valid(Player p) { return p != null && p.getWorld().equals(liveWorld()); }
    private void strengthen(Player p) {
        if (!valid(p)) return;
        double max = Math.max(20, Math.min(2048, getConfig().getDouble("bot.max-health", 60)));
        var attribute = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attribute != null) attribute.setBaseValue(max);
        p.setHealth(Math.min(max, p.getMaxHealth()));
        p.setFoodLevel(20); p.setSaturation(20);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer(); if (!p.getName().equalsIgnoreCase(getConfig().getString("bot-name", "RodeoBot"))) return;
        botId=p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (valid(p)) { strengthen(p); if (start==null) start = p.getLocation().clone(); protectedUntil=System.currentTimeMillis()+getConfig().getLong("bot.spawn-protection-seconds",8)*1000; }
        }, 10L);
    }
    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !valid(p) || !p.getName().equalsIgnoreCase(getConfig().getString("bot-name", "RodeoBot"))) return;
        if (System.currentTimeMillis() < protectedUntil) { e.setCancelled(true); return; }
        e.setDamage(e.getDamage()*Math.max(0.05,Math.min(1,getConfig().getDouble("bot.damage-multiplier",0.5))));
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p=e.getEntity();
        if (!valid(p) || !p.getName().equalsIgnoreCase(getConfig().getString("bot-name", "RodeoBot"))) return;
        deaths++;
        e.setKeepInventory(getConfig().getBoolean("bot.keep-inventory", true));
        e.getDrops().clear(); e.setDroppedExp(0);
        Bukkit.broadcastMessage(ChatColor.RED+"[LIVE] Bot zginal! Runda "+(deaths+1)+" zacznie sie po odrodzeniu.");
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        if (!e.getPlayer().getName().equalsIgnoreCase(getConfig().getString("bot-name", "RodeoBot"))) return;
        if (start!=null && start.getWorld()!=null) e.setRespawnLocation(start.clone());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p=e.getPlayer(); if (valid(p)) { strengthen(p); p.setHealth(p.getMaxHealth()); protectedUntil=System.currentTimeMillis()+getConfig().getLong("bot.spawn-protection-seconds",8)*1000; trail.clear(); blocksToRemove.clear(); previousSampleX=Long.MIN_VALUE; }
        }, 2L);
    }
    private void tick() {
        Player p=bot(); if (!running || !valid(p) || p.isDead()) return;
        if (p.getTicksLived()% (20*Math.max(1,getConfig().getInt("bot.regen-every-seconds",4)))==0) {
            p.setHealth(Math.min(p.getMaxHealth(), p.getHealth()+getConfig().getDouble("bot.regen-health",2)));
            p.setFoodLevel(20);
        }
        if (getConfig().getBoolean("terrain.enabled",true)) {
            int sample=Math.max(2,getConfig().getInt("terrain.path-sample-blocks",5));
            int x=p.getLocation().getBlockX(), z=p.getLocation().getBlockZ();
            if (previousSampleX==Long.MIN_VALUE || (x-previousSampleX)*(x-previousSampleX)+(z-previousSampleZ)*(z-previousSampleZ)>=sample*sample) {
                trail.addLast(p.getLocation().clone()); previousSampleX=x; previousSampleZ=z;
            }
            int distance=Math.max(12,getConfig().getInt("terrain.distance-behind",24));
            // Znikanie jest oparte na historii trasy, a nie na osi X: dziala rowniez przy zakretach.
            while (!trail.isEmpty() && trail.peekFirst().distanceSquared(p.getLocation())>distance*distance) {
                Location old=trail.pollFirst(); queueTrailSegment(old, p.getLocation());
            }
            int perTick=Math.max(1,Math.min(300,getConfig().getInt("terrain.max-blocks-per-tick",80)));
            World world=p.getWorld();
            for (int i=0;i<perTick && !blocksToRemove.isEmpty(); i++) {
                BlockPos b=blocksToRemove.pollFirst();
                if (Math.pow(b.x()-p.getLocation().getBlockX(),2)+Math.pow(b.z()-p.getLocation().getBlockZ(),2) <= distance*distance) continue;
                if (!world.isChunkLoaded(b.x()>>4,b.z()>>4)) continue;
                if (b.y()>=world.getMinHeight() && b.y()<world.getMaxHeight()) world.getBlockAt(b.x(),b.y(),b.z()).setType(Material.AIR,false);
            }
        }
    }
    private void queueTrailSegment(Location old, Location current) {
        World w=old.getWorld(); if (w==null) return;
        int radius=Math.min(8,Math.max(1,getConfig().getInt("terrain.trail-width",7)/2));
        // Ograniczona kolejka, bez prob usuwania calych chunkow w jednej klatce.
        if (blocksToRemove.size()>200000) return;
        for (int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
            int x=old.getBlockX()+dx,z=old.getBlockZ()+dz;
            if (!w.isChunkLoaded(x>>4,z>>4)) continue;
            for(int y=w.getMinHeight();y<w.getMaxHeight();y++) blocksToRemove.addLast(new BlockPos(x,y,z));
        }
    }
    private void spawnGift(String gift, String username) {
        Player p=bot(); if (!running || !valid(p) || p.isDead()) return;
        String base="mobs.gift-types."+gift.toLowerCase(Locale.ROOT)+".";
        String type=getConfig().getString(base+"type"); if (type==null) return;
        EntityType entityType;
        try {entityType=EntityType.valueOf(type.toUpperCase(Locale.ROOT));}catch(IllegalArgumentException ex){return;}
        if (!entityType.isAlive() || !entityType.isSpawnable()) return;
        int amount=Math.max(1,Math.min(10,getConfig().getInt(base+"count",1)));
        int limit=Math.max(1,getConfig().getInt("mobs.max-live-mobs",30));
        giftMobs.removeIf(id -> Bukkit.getEntity(id)==null);
        String nick=username.replaceAll("[\\p{Cntrl}§]", "").trim();
        if (nick.length()>24) nick=nick.substring(0,24);
        for(int i=0;i<amount && giftMobs.size()<limit;i++) {
            Location where=p.getLocation().clone();
            double angle=Math.random()*Math.PI*2;
            int r=Math.max(3,getConfig().getInt("mobs.spawn-distance",7));
            where.add(Math.cos(angle)*r,0,Math.sin(angle)*r);
            where.setY(where.getWorld().getHighestBlockYAt(where)+1);
            Entity entity;
            try{ entity=where.getWorld().spawnEntity(where,entityType); }catch(Exception ex){continue;}
            entity.setCustomName(ChatColor.YELLOW+nick+ChatColor.RED+" | "+gift);
            entity.setCustomNameVisible(true);
            giftMobs.add(entity.getUniqueId());
            UUID id=entity.getUniqueId();
            Bukkit.getScheduler().runTaskLater(this, ()->{Entity mob=Bukkit.getEntity(id);if(mob!=null)mob.remove();giftMobs.remove(id);},Math.max(5,getConfig().getInt("mobs.lifetime-seconds",90))*20L);
        }
    }
    private void startBridge() {
        if (!getConfig().getBoolean("bridge.enabled",true))return;
        String token=getConfig().getString("bridge.token", "");
        if (token.length()<20 || token.startsWith("CHANGE_ME")) {getLogger().warning("Bridge WYLACZONY: ustaw losowy token (min. 20 znakow) w config.yml");return;}
        try {
            String addr=getConfig().getString("bridge.bind","127.0.0.1");
            if (!addr.equals("127.0.0.1") && !addr.equals("::1")) {getLogger().warning("Bridge moze nasluchiwac tylko na loopback.");return;}
            bridge=HttpServer.create(new InetSocketAddress(addr,getConfig().getInt("bridge.port",18765)), 8);
            bridge.createContext("/gift", (HttpExchange ex)->{
                try {
                    if (!ex.getRequestMethod().equals("POST") || !token.equals(ex.getRequestHeaders().getFirst("X-Rodeo-Token"))) {send(ex,403,"Forbidden");return;}
                    byte[] payload=ex.getRequestBody().readNBytes(300);
                    String body=new String(payload,StandardCharsets.UTF_8).trim();
                    String[] parts=body.split("\\|",2);
                    if (parts.length!=2 || !parts[0].matches("[a-zA-Z_]{1,32}") || parts[1].length()>100) {send(ex,400,"Expected gift|nickname");return;}
                    Bukkit.getScheduler().runTask(this, ()->spawnGift(parts[0],parts[1]));
                    send(ex,202,"Queued");
                } catch(Exception err) {try{send(ex,500,"Error");}catch(Exception ignored){}}
            });
            bridge.setExecutor(Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RodeoBridge");t.setDaemon(true);return t;}));
            bridge.start(); getLogger().info("Bridge aktywny: "+addr+":"+bridge.getAddress().getPort());
        } catch(IOException e){getLogger().warning("Nie mozna uruchomic bridge: "+e.getMessage());}
    }
    private void send(HttpExchange ex,int status,String response)throws IOException {byte[] data=response.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(status,data.length);try(OutputStream out=ex.getResponseBody()){out.write(data);}}
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if (!sender.hasPermission("rodeolive.admin"))return true;
        if(args.length==0){sender.sendMessage("/rodeolive start | stop | reset | test <gift> <nick> | stats | reload");return true;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "start" -> {Player p=bot();if(!valid(p)){sender.sendMessage("Bot musi byc online w swiecie "+getConfig().getString("world"));break;} if(start==null)start=p.getLocation().clone(); running=true;strengthen(p);sender.sendMessage("LIVE rozpoczęty. Start: "+start.getBlockX()+","+start.getBlockZ());}
            case "stop" -> {running=false;sender.sendMessage("LIVE zatrzymany.");}
            case "reset" -> {Player p=bot();if(!valid(p) || start==null){sender.sendMessage("Bot/start niedostepny.");break;} p.teleport(start);trail.clear();blocksToRemove.clear();previousSampleX=Long.MIN_VALUE;sender.sendMessage("Nowa runda - teleport na poczatek.");}
            case "test" -> {if(args.length<3){sender.sendMessage("/rodeolive test rose NickWidza");break;}spawnGift(args[1],args[2]);sender.sendMessage("Test gift: "+args[1]+" od "+args[2]);}
            case "stats" -> sender.sendMessage("LIVE="+running+", bot="+(valid(bot())?"online":"offline")+", smierci="+deaths+", moby="+giftMobs.size()+", kolejka blokow="+blocksToRemove.size());
            case "reload" -> {reloadConfig();if(bridge!=null){bridge.stop(0);bridge=null;}startBridge();sender.sendMessage("Konfiguracja przeladowana.");}
            default -> sender.sendMessage("Nieznana komenda.");
        }return true;
    }
}
