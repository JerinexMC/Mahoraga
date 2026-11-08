package com.mahoraga;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MahoragaPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, FrozenState> frozen = new HashMap<>();
    private final Set<UUID> ritualsInProgress = new HashSet<>();
    private NamespacedKey ownerKey;
    private int cooldownSeconds;
    private int freezeRadius;
    private int sculkRadius;
    private boolean sculkSpread;
    private List<String> extraAllowed;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ownerKey = new NamespacedKey(this, "mahoraga_owner");
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Mahoraga ritual plugin enabled.");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(frozen.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) unfreeze(entity);
        }
        frozen.clear();
        ritualsInProgress.clear();
    }

    private void loadSettings() {
        cooldownSeconds = Math.max(0, getConfig().getInt("cooldown-seconds", 1));
        freezeRadius = Math.max(1, getConfig().getInt("freeze-radius", 15));
        sculkRadius = Math.max(1, getConfig().getInt("sculk-radius", 15));
        sculkSpread = getConfig().getBoolean("sculk-spread", true);
        extraAllowed = getConfig().getStringList("extra-allowed-players");
        if (extraAllowed == null) extraAllowed = Collections.emptyList();
    }

    private boolean isAllowed(Player player) {
        if (player.isOp()) return true;
        for (String name : extraAllowed) {
            if (name.equalsIgnoreCase(player.getName())) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!event.getMessage().trim().equalsIgnoreCase("WARA WARA")) return;
        event.setCancelled(true); // Hide the actual trigger message from public chat.
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> beginRitual(player));
    }

    private void beginRitual(Player player) {
        if (!player.isOnline()) return;
        if (!isAllowed(player)) {
            player.sendMessage(ChatColor.RED + "You are not allowed to summon Mahoraga.");
            return;
        }
        if (!ritualsInProgress.isEmpty()) {
            player.sendMessage(ChatColor.DARK_RED + "Another Mahoraga ritual is already in progress.");
            return;
        }
        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long left = Math.max(1L, (readyAt - now + 999L) / 1000L);
            player.sendMessage(ChatColor.RED + "Mahoraga is not ready. Cooldown: " + left + "s");
            return;
        }

        Location origin = player.getLocation().clone();
        if (hasNearbyMahoraga(origin, freezeRadius)) {
            player.sendMessage(ChatColor.RED + "A Mahoraga is already active nearby.");
            return;
        }

        cooldowns.put(player.getUniqueId(), now + cooldownSeconds * 1000L);
        ritualsInProgress.add(player.getUniqueId());
        broadcastIncantation(origin.getWorld());
        freezeNearby(origin, freezeRadius);

        new RitualTask(player, origin).runTaskTimer(this, 0L, 1L);
    }

    private void broadcastIncantation(World world) {
        String line = ChatColor.DARK_PURPLE + "✦ " + ChatColor.LIGHT_PURPLE + ChatColor.BOLD
                + "Furube Yurayura... Yatsutsuka no Tsurugi Ikai Shinshō Makora"
                + ChatColor.DARK_PURPLE + " ✦";
        for (Player online : world.getPlayers()) online.sendMessage(line);
        for (Player online : world.getPlayers()) {
            online.sendTitle(ChatColor.DARK_PURPLE + "MAHORAGA", ChatColor.LIGHT_PURPLE + "Furube Yurayura...", 5, 35, 10);
        }
    }

    private boolean hasNearbyMahoraga(Location center, double radius) {
        double r2 = radius * radius;
        for (Entity entity : center.getWorld().getEntities()) {
            if (entity.getType() != EntityType.WARDEN) continue;
            if (!entity.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) continue;
            if (entity.getLocation().distanceSquared(center) <= r2) return true;
        }
        return false;
    }

    private void freezeNearby(Location center, double radius) {
        double r2 = radius * radius;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity.isDead() || !entity.isValid()) continue;
            if (entity.getLocation().distanceSquared(center) > r2) continue;
            freeze(entity);
        }
        // The summoner is guaranteed to be included even if the nearby-entity query omits its own player.
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= r2) freeze(player);
        }
    }

    private void freeze(Entity entity) {
        if (frozen.containsKey(entity.getUniqueId())) return;
        FrozenState state = new FrozenState(entity);
        frozen.put(entity.getUniqueId(), state);
        entity.setGravity(false);
        entity.setVelocity(new Vector(0, 0, 0));
        if (entity instanceof Mob mob) mob.setAI(false);
    }

    private void unfreeze(Entity entity) {
        FrozenState state = frozen.remove(entity.getUniqueId());
        if (state == null) return;
        if (!entity.isValid()) return;
        entity.setGravity(state.gravity);
        if (entity instanceof Mob mob) mob.setAI(state.ai);
        if (entity instanceof Player player) {
            player.setWalkSpeed(state.walkSpeed);
            player.setFlySpeed(state.flySpeed);
        }
        entity.setVelocity(state.velocity);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenPlayerMove(PlayerMoveEvent event) {
        if (!frozen.containsKey(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMahoragaTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Warden warden) || event.getTarget() == null) return;
        String owner = warden.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (owner != null && owner.equals(event.getTarget().getUniqueId().toString())) event.setCancelled(true);
    }

    // A Warden's sonic boom can damage its summoner even when it is not targeting them.
    // Cancel direct Warden damage to the owner as a second layer of protection.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMahoragaDamageOwner(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Warden warden) || !(event.getEntity() instanceof Player victim)) return;
        String owner = warden.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (owner != null && owner.equals(victim.getUniqueId().toString())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // A disconnect must not leave a player permanently frozen in plugin state.
        unfreeze(event.getPlayer());
    }

    private final class RitualTask extends BukkitRunnable {
        private final Player summoner;
        private final Location center;
        private int tick = 0;
        private int lastSpreadStep = 0;

        private RitualTask(Player summoner, Location center) {
            this.summoner = summoner;
            this.center = center;
        }

        @Override
        public void run() {
            if (!summoner.isOnline() || !summoner.getWorld().equals(center.getWorld())) {
                finishWithoutSpawn();
                cancel();
                return;
            }

            keepFrozenEntitiesStill();

            // Five heartbeat pulses, exactly one second apart.
            if (tick % 20 == 0 && tick <= 80) heartbeat();

            // Sculk expands in twenty visible rings over five seconds.
            int spreadStep = Math.min(20, tick / 5 + 1);
            if (sculkSpread && spreadStep > lastSpreadStep) {
                spreadRing(lastSpreadStep * (sculkRadius / 20.0), spreadStep * (sculkRadius / 20.0));
                lastSpreadStep = spreadStep;
            }

            tick++;
            if (tick >= 100) {
                summonWarden();
                cancel();
            }
        }

        private void keepFrozenEntitiesStill() {
            for (UUID id : new ArrayList<>(frozen.keySet())) {
                Entity entity = Bukkit.getEntity(id);
                if (entity == null || !entity.isValid() || entity.isDead()) {
                    frozen.remove(id);
                    continue;
                }
                FrozenState state = frozen.get(id);
                if (state == null) continue;
                if (!entity.getWorld().equals(state.location.getWorld())) continue;
                if (entity.getLocation().distanceSquared(state.location) > 0.0001) {
                    entity.teleport(state.location);
                }
                entity.setVelocity(new Vector(0, 0, 0));
            }
        }

        private void heartbeat() {
            for (Player player : center.getWorld().getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= 40 * 40) {
                    player.playSound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.25f, 0.65f);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 7, 0, true, false, false));
                }
            }
            center.getWorld().spawnParticle(org.bukkit.Particle.SCULK_SOUL, center.clone().add(0, 1, 0), 16, 1.2, 0.8, 1.2, 0.02);
        }

        private void spreadRing(double innerRadius, double outerRadius) {
            World world = center.getWorld();
            int minX = center.getBlockX() - sculkRadius;
            int maxX = center.getBlockX() + sculkRadius;
            int minZ = center.getBlockZ() - sculkRadius;
            int maxZ = center.getBlockZ() + sculkRadius;
            double outer2 = outerRadius * outerRadius;
            double inner2 = innerRadius * innerRadius;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double dx = x + 0.5 - center.getX();
                    double dz = z + 0.5 - center.getZ();
                    double d2 = dx * dx + dz * dz;
                    if (d2 > outer2 || d2 <= inner2) continue;
                    Block ground = world.getHighestBlockAt(x, z);
                    if (Math.abs(ground.getY() - center.getBlockY()) > 5) continue;
                    if (isNaturalGround(ground.getType())) {
                        ground.setType(Material.SCULK, false);
                        world.spawnParticle(org.bukkit.Particle.SCULK_CHARGE, ground.getLocation().add(0.5, 1.0, 0.5), 2, 0.25, 0.1, 0.25, 0.02);
                    }
                }
            }
        }

        private void summonWarden() {
            Location surface = center.clone();
            Vector direction = surface.getDirection().setY(0);
            if (direction.lengthSquared() > 0.001) direction.normalize().multiply(2.5);
            surface.add(direction);
            Block ground = center.getWorld().getHighestBlockAt(surface.getBlockX(), surface.getBlockZ());
            surface.setY(ground.getY() + 1.0);

            // Spawn below the sculk-covered surface, then smoothly raise the Warden out of it.
            Location underground = surface.clone().subtract(0, 2.2, 0);
            Warden warden = (Warden) center.getWorld().spawnEntity(underground, EntityType.WARDEN);
            warden.setPersistent(true);
            warden.setRemoveWhenFarAway(false);
            warden.setAI(false);
            warden.setGravity(false);
            warden.setCustomName(ChatColor.DARK_PURPLE + "✦ " + ChatColor.LIGHT_PURPLE + ChatColor.BOLD + "MAHORAGA" + ChatColor.DARK_PURPLE + " ✦");
            warden.setCustomNameVisible(true);
            warden.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, summoner.getUniqueId().toString());
            setAttribute(warden, Attribute.MAX_HEALTH, 500.0);
            warden.setHealth(500.0);
            setAttribute(warden, Attribute.ATTACK_DAMAGE, 50.0);
            setAttribute(warden, Attribute.MOVEMENT_SPEED, 0.42);
            setAttribute(warden, Attribute.KNOCKBACK_RESISTANCE, 1.0);
            setAttribute(warden, Attribute.ARMOR, 20.0);

            for (Player player : center.getWorld().getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= 40 * 40) {
                    player.sendTitle(ChatColor.DARK_PURPLE + "八", ChatColor.LIGHT_PURPLE + "THE EIGHT-HANDED SWORD DIVERGENT SILA DIVINE GENERAL MAHORAGA", 0, 45, 15);
                }
            }
            summoner.sendMessage(ChatColor.LIGHT_PURPLE + "Mahoraga is emerging from the sculk...");
            // A distant dragon growl followed by the Warden's roar announces the emergence.
            center.getWorld().playSound(underground, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 0.55f);
            center.getWorld().playSound(underground, Sound.ENTITY_WARDEN_ROAR, 2.0f, 0.65f);

            new BukkitRunnable() {
                private int riseTick = 0;
                @Override public void run() {
                    if (!warden.isValid() || warden.isDead()) {
                        unfreezeAll();
                        ritualsInProgress.remove(summoner.getUniqueId());
                        cancel();
                        return;
                    }
                    riseTick++;
                    double progress = Math.min(1.0, riseTick / 40.0);
                    Location rising = underground.clone().add(0, 2.2 * progress, 0);
                    warden.teleport(rising);
                    center.getWorld().spawnParticle(org.bukkit.Particle.SCULK_SOUL, rising.clone().add(0, 0.8, 0), 18, 0.65, 0.8, 0.65, 0.035);
                    center.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, rising.clone().add(0, 0.2, 0), 12, 0.6, 0.2, 0.6, 0.0, Material.SCULK.createBlockData());
                    if (riseTick % 10 == 0) center.getWorld().playSound(rising, Sound.BLOCK_SCULK_CATALYST_BLOOM, 1.0f, 0.65f);
                    if (progress >= 1.0) {
                        warden.setGravity(true);
                        warden.setAI(true);
                        center.getWorld().strikeLightningEffect(surface);
                        center.getWorld().playSound(surface, Sound.ENTITY_WARDEN_ROAR, 2.5f, 0.8f);
                        center.getWorld().spawnParticle(org.bukkit.Particle.SCULK_SOUL, surface.clone().add(0, 1, 0), 90, 1.3, 1.2, 1.3, 0.08);
                        unfreezeAll();
                        ritualsInProgress.remove(summoner.getUniqueId());
                        summoner.sendMessage(ChatColor.LIGHT_PURPLE + "Mahoraga has answered your call.");
                        cancel();
                    }
                }
            }.runTaskTimer(MahoragaPlugin.this, 0L, 1L);
        }

        private void finishWithoutSpawn() {
            unfreezeAll();
            ritualsInProgress.remove(summoner.getUniqueId());
        }
    }

    private void unfreezeAll() {
        for (UUID id : new ArrayList<>(frozen.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) unfreeze(entity);
            else frozen.remove(id);
        }
    }

    private boolean isNaturalGround(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM,
                    STONE, ANDESITE, DIORITE, GRANITE, DEEPSLATE, TUFF, CALCITE,
                    MOSS_BLOCK, MUD, CLAY, SAND, RED_SAND, GRAVEL -> true;
            default -> false;
        };
    }

    private void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mahoraga")) return false;
        if (args.length == 2 && args[0].equalsIgnoreCase("cooldown")) {
            if (!sender.hasPermission("mahoraga.admin")) {
                sender.sendMessage(ChatColor.RED + "You need operator permission to change the cooldown.");
                return true;
            }
            try {
                int seconds = Integer.parseInt(args[1]);
                if (seconds < 0 || seconds > 86400) throw new NumberFormatException();
                cooldownSeconds = seconds;
                getConfig().set("cooldown-seconds", seconds);
                saveConfig();
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "Mahoraga cooldown set to " + seconds + " second(s).");
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Usage: /mahoraga cooldown <0-86400>");
            }
            return true;
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Say WARA WARA to summon Mahoraga.");
        sender.sendMessage(ChatColor.GRAY + "Admin: /mahoraga cooldown <seconds>");
        return true;
    }

    private static final class FrozenState {
        private final Location location;
        private final Vector velocity;
        private final boolean gravity;
        private final boolean ai;
        private final float walkSpeed;
        private final float flySpeed;

        private FrozenState(Entity entity) {
            this.location = entity.getLocation().clone();
            this.velocity = entity.getVelocity().clone();
            this.gravity = entity.hasGravity();
            this.ai = entity instanceof Mob mob && mob.hasAI();
            this.walkSpeed = entity instanceof Player player ? player.getWalkSpeed() : 0.2f;
            this.flySpeed = entity instanceof Player player ? player.getFlySpeed() : 0.1f;
        }
    }
}
