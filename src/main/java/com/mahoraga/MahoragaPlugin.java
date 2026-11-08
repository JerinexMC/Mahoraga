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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
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
        unfreezeAll();

        frozen.clear();
        ritualsInProgress.clear();
    }

    private void loadSettings() {
        cooldownSeconds = Math.max(0, getConfig().getInt("cooldown-seconds", 1));
        freezeRadius = Math.max(1, getConfig().getInt("freeze-radius", 15));
        sculkRadius = Math.max(1, getConfig().getInt("sculk-radius", 15));

        sculkSpread = getConfig().getBoolean("sculk-spread", true);

        extraAllowed = getConfig().getStringList("extra-allowed-players");

        if (extraAllowed == null) {
            extraAllowed = Collections.emptyList();
        }
    }

    private boolean isAllowed(Player player) {
        if (player.isOp()) return true;

        for (String name : extraAllowed) {
            if (name.equalsIgnoreCase(player.getName())) {
                return true;
            }
        }

        return false;
    }

    // WARA WARA CHAT TRIGGER

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!event.getMessage().trim().equalsIgnoreCase("WARA WARA")) {
            return;
        }

        event.setCancelled(true);

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
        String line =
                ChatColor.DARK_RED + "" + ChatColor.BOLD +
                "Furube Yurayura... Yatsutsuka no Tsurugi Ikai Shinshō Makora";

        for (Player online : world.getPlayers()) {
            online.sendMessage(line);

            online.sendTitle(
                    ChatColor.DARK_RED + "" + ChatColor.BOLD + "MAHORAGA",
                    ChatColor.GRAY + "Furube Yurayura...",
                    5, 35, 10
            );
        }

        world.playSound(
                world.getSpawnLocation(),
                Sound.BLOCK_SCULK_SHRIEKER_SHRIEK,
                2.0f,
                0.5f
        );
    }

    // CHECK FOR EXISTING MAHORAGA

    private boolean hasNearbyMahoraga(Location center, double radius) {
        double r2 = radius * radius;

        for (Entity entity : center.getWorld().getEntities()) {
            if (entity.getType() != EntityType.WARDEN) continue;

            if (!entity.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                continue;
            }

            if (entity.getLocation().distanceSquared(center) <= r2) {
                return true;
            }
        }

        return false;
    }

    // FREEZE ENTITIES

    private void freezeNearby(Location center, double radius) {
        double r2 = radius * radius;

        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity.isDead() || !entity.isValid()) continue;

            if (entity.getLocation().distanceSquared(center) > r2) continue;

            freeze(entity);
        }

        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= r2) {
                freeze(player);
            }
        }
    }

    private void freeze(Entity entity) {
        if (frozen.containsKey(entity.getUniqueId())) return;

        FrozenState state = new FrozenState(entity);

        frozen.put(entity.getUniqueId(), state);

        entity.setGravity(false);
        entity.setVelocity(new Vector(0, 0, 0));

        if (entity instanceof Mob mob) {
            mob.setAI(false);
        }
    }

    private void unfreeze(Entity entity) {
        FrozenState state = frozen.remove(entity.getUniqueId());

        if (state == null) return;
        if (!entity.isValid()) return;

        entity.setGravity(state.gravity);

        if (entity instanceof Mob mob) {
            mob.setAI(state.ai);
        }

        if (entity instanceof Player player) {
            player.setWalkSpeed(state.walkSpeed);
            player.setFlySpeed(state.flySpeed);
        }

        entity.setVelocity(state.velocity);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenPlayerMove(PlayerMoveEvent event) {
        if (!frozen.containsKey(event.getPlayer().getUniqueId())) return;
        if (event.getTo() == null) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()) {

            event.setTo(from);
        }
    }

    // MAHORAGA WILL NEVER ATTACK HIS SUMMONER

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMahoragaTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Warden warden)) return;
        if (event.getTarget() == null) return;

        String owner = warden.getPersistentDataContainer()
                .get(ownerKey, PersistentDataType.STRING);

        if (owner == null) return;

        if (owner.equals(event.getTarget().getUniqueId().toString())) {
            event.setCancelled(true);
            warden.setTarget(null);
        }
    }

    // PROTECT OWNER FROM WARDEN DAMAGE, INCLUDING SONIC BOOM

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMahoragaDamageOwner(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Warden warden)) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        String owner = warden.getPersistentDataContainer()
                .get(ownerKey, PersistentDataType.STRING);

        if (owner == null) return;

        if (owner.equals(victim.getUniqueId().toString())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        unfreeze(event.getPlayer());
    }

    // THE FIVE SECOND RITUAL

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
            if (!summoner.isOnline()
                    || !summoner.getWorld().equals(center.getWorld())) {

                finishWithoutSpawn();
                cancel();
                return;
            }

            keepFrozenEntitiesStill();

            // FIVE HEARTBEATS, ONE PER SECOND

            if (tick % 20 == 0 && tick <= 80) {
                heartbeat();
            }

            // SCULK SPREADS OVER FIVE SECONDS

            int spreadStep = Math.min(20, tick / 5 + 1);

            if (sculkSpread && spreadStep > lastSpreadStep) {
                spreadRing(
                        lastSpreadStep * (sculkRadius / 20.0),
                        spreadStep * (sculkRadius / 20.0)
                );

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

                if (!entity.getWorld().equals(state.location.getWorld())) {
                    continue;
                }

                if (entity.getLocation().distanceSquared(state.location) > 0.0001) {
                    entity.teleport(state.location);
                }

                entity.setVelocity(new Vector(0, 0, 0));
            }
        }

        private void heartbeat() {
            for (Player player : center.getWorld().getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= 40 * 40) {
                    player.playSound(
                            center,
                            Sound.ENTITY_WARDEN_HEARTBEAT,
                            1.5f,
                            0.55f
                    );

                    player.addPotionEffect(
                            new PotionEffect(
                                    PotionEffectType.DARKNESS,
                                    7,
                                    0,
                                    true,
                                    false,
                                    false
                            )
                    );
                }
            }

            center.getWorld().spawnParticle(
                    org.bukkit.Particle.SCULK_SOUL,
                    center.clone().add(0, 1, 0),
                    20,
                    1.2,
                    0.8,
                    1.2,
                    0.02
            );
        }

        // SCULK SPREADS IN A CIRCLE

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

                    if (Math.abs(ground.getY() - center.getBlockY()) > 5) {
                        continue;
                    }

                    if (isNaturalGround(ground.getType())) {
                        ground.setType(Material.SCULK, false);

                        world.spawnParticle(
                                org.bukkit.Particle.SCULK_CHARGE,
                                ground.getLocation().add(0.5, 1.0, 0.5),
                                3,
                                0.25,
                                0.1,
                                0.25,
                                0.02
                        );
                    }
                }
            }
        }

        // SUMMON MAHORAGA UNDERGROUND

        private void summonWarden() {
            Location spawn = center.clone();

            Vector direction = spawn.getDirection().setY(0);

            if (direction.lengthSquared() > 0.001) {
                direction.normalize().multiply(2.5);
            }

            spawn.add(direction);

            World world = center.getWorld();

            int surfaceY = world.getHighestBlockYAt(
                    spawn.getBlockX(),
                    spawn.getBlockZ()
            ) + 1;

            double finalY = surfaceY + 0.05;
            double undergroundY = finalY - 2.5;

            spawn.setY(undergroundY);

            Warden warden = (Warden) world.spawnEntity(spawn, EntityType.WARDEN);

            warden.setPersistent(true);
            warden.setRemoveWhenFarAway(false);

            // CLEAN, DARK NAME. NO PINK OR STARS.

            warden.setCustomName(
                    ChatColor.DARK_RED + "" + ChatColor.BOLD + "MAHORAGA"
            );

            warden.setCustomNameVisible(true);

            // SAVE SUMMONER UUID

            warden.getPersistentDataContainer().set(
                    ownerKey,
                    PersistentDataType.STRING,
                    summoner.getUniqueId().toString()
            );

            // POWERFUL ATTRIBUTES

            setAttribute(warden, Attribute.MAX_HEALTH, 500.0);
            warden.setHealth(500.0);

            setAttribute(warden, Attribute.ATTACK_DAMAGE, 50.0);
            setAttribute(warden, Attribute.MOVEMENT_SPEED, 0.42);
            setAttribute(warden, Attribute.KNOCKBACK_RESISTANCE, 1.0);
            setAttribute(warden, Attribute.ARMOR, 20.0);

            // DISABLE MOVEMENT WHILE HE RISES

            warden.setAI(false);
            warden.setGravity(false);
            warden.setInvulnerable(true);

            // SCARY SUMMONING SOUNDS

            world.playSound(
                    spawn,
                    Sound.ENTITY_ENDER_DRAGON_GROWL,
                    3.0f,
                    0.55f
            );

            world.playSound(
                    spawn,
                    Sound.BLOCK_SCULK_SHRIEKER_SHRIEK,
                    3.0f,
                    0.6f
            );

            world.spawnParticle(
                    org.bukkit.Particle.SCULK_SOUL,
                    spawn.clone().add(0, 1, 0),
                    100,
                    1.2,
                    1.5,
                    1.2,
                    0.08
            );

            // MAHORAGA RISES OUT OF THE GROUND

            new BukkitRunnable() {

                private int riseTick = 0;

                @Override
                public void run() {
                    if (!warden.isValid() || warden.isDead()) {
                        unfreezeAll();
                        ritualsInProgress.remove(summoner.getUniqueId());
                        cancel();
                        return;
                    }

                    double progress = Math.min(1.0, riseTick / 40.0);

                    double currentY = undergroundY
                            + (finalY - undergroundY) * progress;

                    Location current = spawn.clone();
                    current.setY(currentY);

                    warden.teleport(current);

                    // SCULK PARTICLES AROUND HIS BODY

                    world.spawnParticle(
                            org.bukkit.Particle.SCULK_SOUL,
                            current.clone().add(0, 1.0, 0),
                            12,
                            0.8,
                            1.0,
                            0.8,
                            0.03
                    );

                    world.spawnParticle(
                            org.bukkit.Particle.SCULK_CHARGE,
                            current.clone().add(0, 0.5, 0),
                            5,
                            0.6,
                            0.5,
                            0.6,
                            0.02
                    );

                    // SOUNDS DURING EMERGENCE

                    if (riseTick == 10) {
                        world.playSound(
                                current,
                                Sound.ENTITY_WARDEN_HEARTBEAT,
                                3.0f,
                                0.5f
                        );
                    }

                    if (riseTick == 20) {
                        world.playSound(
                                current,
                                Sound.ENTITY_WARDEN_ROAR,
                                3.0f,
                                0.65f
                        );
                    }

                    if (riseTick == 30) {
                        world.playSound(
                                current,
                                Sound.ENTITY_ENDER_DRAGON_GROWL,
                                3.0f,
                                0.7f
                        );
                    }

                    riseTick++;

                    // FULLY EMERGED

                    if (riseTick >= 40) {
                        warden.teleport(spawn.clone().setY(finalY));

                        warden.setInvulnerable(false);
                        warden.setGravity(true);
                        warden.setAI(true);

                        // MAKE SURE HE DOES NOT TARGET HIS SUMMONER

                        warden.setTarget(null);

                        world.playSound(
                                warden.getLocation(),
                                Sound.ENTITY_WARDEN_SONIC_BOOM,
                                3.0f,
                                0.7f
                        );

                        world.playSound(
                                warden.getLocation(),
                                Sound.ENTITY_WARDEN_ROAR,
                                3.0f,
                                0.8f
                        );

                        world.spawnParticle(
                                org.bukkit.Particle.SCULK_SOUL,
                                warden.getLocation().add(0, 1, 0),
                                150,
                                2.0,
                                1.5,
                                2.0,
                                0.1
                        );

                        for (Player player : world.getPlayers()) {
                            if (player.getLocation().distanceSquared(center) <= 40 * 40) {
                                player.sendTitle(
                                        ChatColor.DARK_RED + "" + ChatColor.BOLD + "八",
                                        ChatColor.RED + "THE DIVINE GENERAL MAHORAGA",
                                        5,
                                        60,
                                        20
                                );
                            }
                        }

                        // ONLY NOW UNFREEZE EVERYONE

                        unfreezeAll();

                        ritualsInProgress.remove(summoner.getUniqueId());

                        summoner.sendMessage(
                                ChatColor.DARK_RED + "Mahoraga has answered your call."
                        );

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

    // UNFREEZE EVERYONE

    private void unfreezeAll() {
        for (UUID id : new ArrayList<>(frozen.keySet())) {
            Entity entity = Bukkit.getEntity(id);

            if (entity != null) {
                unfreeze(entity);
            } else {
                frozen.remove(id);
            }
        }
    }

    // NATURAL BLOCKS THAT CAN TURN INTO SCULK

    private boolean isNaturalGround(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT,
                    PODZOL, MYCELIUM, STONE, ANDESITE, DIORITE,
                    GRANITE, DEEPSLATE, TUFF, CALCITE, MOSS_BLOCK,
                    MUD, CLAY, SAND, RED_SAND, GRAVEL -> true;

            default -> false;
        };
    }

    private void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);

        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    // /MAHORAGA COOLDOWN

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("mahoraga")) {
            return false;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("cooldown")) {
            if (!sender.hasPermission("mahoraga.admin")) {
                sender.sendMessage(
                        ChatColor.RED + "You need operator permission to change the cooldown."
                );

                return true;
            }

            try {
                int seconds = Integer.parseInt(args[1]);

                if (seconds < 0 || seconds > 86400) {
                    throw new NumberFormatException();
                }

                cooldownSeconds = seconds;

                getConfig().set("cooldown-seconds", seconds);
                saveConfig();

                sender.sendMessage(
                        ChatColor.DARK_RED + "Mahoraga cooldown set to "
                                + seconds + " second(s)."
                );

            } catch (NumberFormatException ex) {
                sender.sendMessage(
                        ChatColor.RED + "Usage: /mahoraga cooldown <0-86400>"
                );
            }

            return true;
        }

        sender.sendMessage(
                ChatColor.DARK_RED + "Say WARA WARA to summon Mahoraga."
        );

        sender.sendMessage(
                ChatColor.GRAY + "Admin: /mahoraga cooldown <seconds>"
        );

        return true;
    }

    // SAVE ENTITY STATE BEFORE FREEZING

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

            this.walkSpeed = entity instanceof Player player
                    ? player.getWalkSpeed()
                    : 0.2f;

            this.flySpeed = entity instanceof Player player
                    ? player.getFlySpeed()
                    : 0.1f;
        }
    }
}
