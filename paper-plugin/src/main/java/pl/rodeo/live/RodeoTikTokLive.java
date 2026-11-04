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

/** Rodeo TikTok LIVE 1.2. All Bukkit world/entity operations occur on the primary thread. */
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
    private final Map<UUID,GameMode> cameraModes = new HashMap<>();
    private record Column(int x, int z, int floorY) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("rodeolive")).setExecutor(this);
        running = getConfig().getBoolean("start-enabled", false);
        startBridge();
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("RodeoTikTokLive 1.2. Komenda /rodeolive debug pokazuje swiat i nick bota.");
    }
    @Override public void onDisable() {
        if (task != null) task.cancel();
        if (server != null) server.stop(0);
        pending.clear(); pendingKeys.clear(); giftMobs.clear(); cameraModes.clear();
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
        deaths++;e.setKeepInventory(true);e.getDrops().clear();e.setDroppedExp(0);
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
    // Obserwator moze widziec kamere bota; zwykly spectator nie renderuje jego dloni/miecza.
    private void camera(Player viewer) {
        Player target=bot();
        if(!valid(target)){viewer.sendMessage(ChatColor.RED+"RodeoBot nie jest online w swiecie LIVE.");return;}
        if(viewer.equals(target)){viewer.sendMessage("Nie mozna obserwowac samego siebie.");return;}
        cameraModes.putIfAbsent(viewer.getUniqueId(),viewer.getGameMode());
        viewer.setGameMode(GameMode.SPECTATOR);
        viewer.teleport(target.getLocation());
        Bukkit.getScheduler().runTaskLater(this,()->{
            if(viewer.isOnline()&&valid(target)&&viewer.getGameMode()==GameMode.SPECTATOR){
                viewer.setSpectatorTarget(target);
                viewer.sendMessage(ChatColor.GREEN+"Kamera bota aktywna. /rodeolive uncamera przywraca widok.");
            }
        },2L);
    }
    private void uncamera(Player viewer) {
        if(viewer.getGameMode()==GameMode.SPECTATOR) viewer.setSpectatorTarget(null);
        GameMode previous=cameraModes.remove(viewer.getUniqueId());
        if(previous!=null)viewer.setGameMode(previous);
        viewer.sendMessage("Widok kamery zakonczony.");
    }
    @EventHandler public void onQuit(PlayerQuitEvent e){cameraModes.remove(e.getPlayer().getUniqueId());}
    private void tick() {
        Player p=bot();if(!running||!valid(p)||p.isDead())return;
        if(p.getTicksLived()% (20*Math.max(1,getConfig().getInt("bot.regen-every-seconds",4)))==0){
            p.setHealth(Math.min(p.getMaxHealth(),p.getHealth()+getConfig().getDouble("bot.regen-health",2)));
            p.setFoodLevel(20);
        }
        if(!getConfig().getBoolean("lane.enabled",true) || roundStart==null)return;
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
    /** Spawn gift mobs onto a safe short platform ahead of the bot; return actual count. */
    private int spawnGift(String event,String username) {
        Player p=bot();
        if(!running||!valid(p)||p.isDead())return 0;
        String key="mobs.events."+event.toLowerCase(Locale.ROOT)+".";
        String raw=getConfig().getString(key+"type","");
        EntityType type;
        try {type=EntityType.valueOf(raw.toUpperCase(Locale.ROOT));}
        catch(Exception ex){getLogger().warning("Nieznane zdarzenie/mob: "+event);return 0;}
        if(!type.isAlive()||!type.isSpawnable())return 0;
        giftMobs.removeIf(id->Bukkit.getEntity(id)==null);
        int limit=Math.max(1,getConfig().getInt("mobs.max-live-mobs",30));
        int count=Math.max(1,Math.min(10,getConfig().getInt(key+"count",1)));
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
            try {mob=w.spawnEntity(loc,type);}
            catch(Exception ex){getLogger().warning("Nie mozna przywolac "+type+": "+ex.getMessage());continue;}
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
                if(parts.length!=2||!parts[0].matches("[a-zA-Z_]{1,32}")||parts[1].isBlank()||parts[1].length()>90){send(ex,400,"{\"error\":\"invalid payload\"}");return;}
                Bukkit.getScheduler().runTask(this,()->spawnGift(parts[0],parts[1]));
                send(ex,202,"{\"queued\":true}");
            });
            server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"RodeoBridge");t.setDaemon(true);return t;}));
            server.start();getLogger().info("Bridge nasluchuje: "+bind+":"+server.getAddress().getPort());
        }catch(IOException e){getLogger().warning("Bridge nie wystartowal: "+e.getMessage());}
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
                // Save fixed start near spawn; this is also the elevation of the flattened corridor.
                roundStart=p.getLocation().clone();
                clearPending();strengthen(p);
                sender.sendMessage(ChatColor.GREEN+"LIVE start! Bot: "+p.getName()+", swiat: "+p.getWorld().getName()+", kierunek "+getConfig().getString("lane.direction","EAST"));
            }
            case "stop" -> {running=false;clearPending();sender.sendMessage("LIVE zatrzymany.");}
            case "reset" -> {
                Player p=bot();if(!valid(p)||roundStart==null){sender.sendMessage("Najpierw uruchom LIVE.");break;}
                p.teleport(roundStart.clone());clearPending();sender.sendMessage("Bot cofniety do startu. Runda od nowa.");
            }
            case "test" -> {
                if(args.length<3){sender.sendMessage("/rodeolive test rose Nick");break;}
                int n=spawnGift(args[1],String.join(" ",Arrays.copyOfRange(args,2,args.length)));
                sender.sendMessage("Test "+args[1]+": przywolano "+n+" mobow. Sprawdz /rodeolive stats.");
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
                    sender.sendMessage(ChatColor.YELLOW+id+ChatColor.WHITE+" -> "+getConfig().getString(key+"type","?")+" x"+getConfig().getInt(key+"count",1)+" | "+getConfig().getString(key+"description",id));
                }
            }
            case "stats" -> sender.sendMessage("LIVE="+running+", bot="+(valid(bot())?"online":"offline/wrong-world")+", deaths="+deaths+", mobs="+giftMobs.size()+", lane queue="+pending.size());
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
