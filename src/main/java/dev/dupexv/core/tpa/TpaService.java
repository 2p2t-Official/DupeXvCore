package dev.dupexv.core.tpa;

import dev.dupexv.core.DupeXvCore;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TpaService {

    enum Kind {
        TO,
        HERE
    }

    private final DupeXvCore plugin;
    private final ConcurrentHashMap<UUID, Request> outgoing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Request>> incoming = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private volatile int warmupSeconds = 5;
    private volatile int cooldownSeconds = 5;
    private volatile int timeoutSeconds = 60;

    public TpaService(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        warmupSeconds = Math.max(0, plugin.getConfig().getInt("tpa.warmup", 5));
        cooldownSeconds = Math.max(0, plugin.getConfig().getInt("tpa.cooldown", 5));
        timeoutSeconds = Math.max(1, plugin.getConfig().getInt("tpa.timeout", 60));
    }

    public void shutdown() {
        for (Request request : outgoing.values()) {
            dropRequest(request);
        }
        for (Warmup warmup : warmups.values()) {
            if (warmup.task != null) {
                warmup.task.cancel();
            }
        }
        outgoing.clear();
        incoming.clear();
        warmups.clear();
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    public void requestTo(Player from, String name) {
        request(from, name, Kind.TO);
    }

    public void requestHere(Player from, String name) {
        request(from, name, Kind.HERE);
    }

    public void accept(Player player, String name) {
        if (warmups.containsKey(player.getUniqueId())) {
            tell(player, "tpa.wait");
            return;
        }
        Request request = findIncoming(player, name);
        if (request == null || !request.alive.get()) {
            tell(player, "tpa.none");
            return;
        }
        Player sender = Bukkit.getPlayer(request.sender);
        if (sender == null || !sender.isOnline()) {
            dropRequest(request);
            tell(player, "tpa.not-online", "player", request.senderName);
            return;
        }
        if (!dropRequest(request)) {
            tell(player, "tpa.none");
            return;
        }
        tell(player, "tpa.accepted", "player", sender.getName());
        tell(sender, "tpa.accepted-other", "player", player.getName());
        Player mover = request.kind == Kind.TO ? sender : player;
        Player dest = request.kind == Kind.TO ? player : sender;
        startWarmup(mover, dest, request.sender);
    }

    public void deny(Player player, String name) {
        Request request = findIncoming(player, name);
        if (request == null || !dropRequest(request)) {
            tell(player, "tpa.none");
            return;
        }
        tell(player, "tpa.denied", "player", request.senderName);
        Player sender = Bukkit.getPlayer(request.sender);
        if (sender != null) {
            tell(sender, "tpa.denied-other", "player", player.getName());
        }
    }

    public void cancel(Player player, String name) {
        Request request = outgoing.get(player.getUniqueId());
        if (request == null || !request.alive.get()) {
            tell(player, "tpa.none");
            return;
        }
        if (name != null && !name.isEmpty()) {
            Player target = findPlayer(name);
            if (target == null || !target.getUniqueId().equals(request.target)) {
                tell(player, "tpa.none");
                return;
            }
        }
        if (!dropRequest(request)) {
            tell(player, "tpa.none");
            return;
        }
        tell(player, "tpa.cancelled", "player", request.targetName);
        Player target = Bukkit.getPlayer(request.target);
        if (target != null) {
            tell(target, "tpa.cancelled-other", "player", player.getName());
        }
    }

    public void cancelWarmup(Player player, String messageKey) {
        Warmup warmup = warmups.remove(player.getUniqueId());
        if (warmup == null) {
            return;
        }
        if (warmup.task != null) {
            warmup.task.cancel();
        }
        if (messageKey != null) {
            tell(player, messageKey);
        }
    }

    public void onLeave(Player player) {
        UUID id = player.getUniqueId();
        cancelWarmup(player, null);
        for (Warmup warmup : warmups.values()) {
            if (id.equals(warmup.dest) || id.equals(warmup.requester)) {
                Player mover = Bukkit.getPlayer(warmup.mover);
                if (mover != null && mover.isOnline()) {
                    cancelWarmup(mover, "tpa.offline");
                } else {
                    Warmup removed = warmups.remove(warmup.mover);
                    if (removed != null && removed.task != null) {
                        removed.task.cancel();
                    }
                }
            }
        }
        Request out = outgoing.get(id);
        if (out != null && dropRequest(out)) {
            Player target = Bukkit.getPlayer(out.target);
            if (target != null && target.isOnline()) {
                tell(target, "tpa.cancelled-other", "player", out.senderName);
            }
        }
        ConcurrentHashMap<UUID, Request> in = incoming.remove(id);
        if (in != null) {
            for (Request request : in.values()) {
                if (dropRequest(request)) {
                    Player sender = Bukkit.getPlayer(request.sender);
                    if (sender != null && sender.isOnline()) {
                        tell(sender, "tpa.not-online", "player", request.targetName);
                    }
                }
            }
        }
    }

    public List<String> matchOnline(Player viewer, String prefix, boolean excludeSelf) {
        String start = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (excludeSelf && player.equals(viewer)) {
                continue;
            }
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                names.add(player.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    public List<String> matchIncoming(Player viewer, String prefix) {
        ConcurrentHashMap<UUID, Request> map = incoming.get(viewer.getUniqueId());
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        String start = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Request request : map.values()) {
            if (!request.alive.get()) {
                continue;
            }
            Player sender = Bukkit.getPlayer(request.sender);
            String name = sender != null ? sender.getName() : request.senderName;
            if (name.toLowerCase(Locale.ROOT).startsWith(start)) {
                names.add(name);
            }
        }
        Collections.sort(names);
        return names;
    }

    public List<String> matchOutgoing(Player viewer, String prefix) {
        Request request = outgoing.get(viewer.getUniqueId());
        if (request == null || !request.alive.get()) {
            return List.of();
        }
        Player target = Bukkit.getPlayer(request.target);
        String name = target != null ? target.getName() : request.targetName;
        if (name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return List.of(name);
        }
        return List.of();
    }

    private void request(Player from, String name, Kind kind) {
        Player to = findPlayer(name);
        if (to == null || !to.isOnline()) {
            tell(from, "tpa.not-online", "player", name);
            return;
        }
        if (from.getUniqueId().equals(to.getUniqueId())) {
            tell(from, "tpa.self");
            return;
        }
        if (warmups.containsKey(from.getUniqueId())) {
            tell(from, "tpa.wait");
            return;
        }
        long left = cooldownLeft(from.getUniqueId());
        if (left > 0) {
            tell(from, "tpa.cooldown", "seconds", left);
            return;
        }
        Request existing = outgoing.get(from.getUniqueId());
        if (existing != null && existing.alive.get() && existing.target.equals(to.getUniqueId())) {
            tell(from, "tpa.already");
            return;
        }
        if (existing != null && existing.alive.get()) {
            dropRequest(existing);
            Player old = Bukkit.getPlayer(existing.target);
            if (old != null && old.isOnline()) {
                tell(old, "tpa.cancelled-other", "player", from.getName());
            }
        }
        Request request = new Request(from.getUniqueId(), to.getUniqueId(), from.getName(), to.getName(), kind);
        outgoing.put(from.getUniqueId(), request);
        incoming.computeIfAbsent(to.getUniqueId(), id -> new ConcurrentHashMap<>()).put(from.getUniqueId(), request);
        request.timeout = Bukkit.getAsyncScheduler().runDelayed(plugin, task -> expire(request), timeoutSeconds, TimeUnit.SECONDS);
        tell(from, "tpa.sent", "player", to.getName());
        if (kind == Kind.TO) {
            tell(to, "tpa.incoming", "player", from.getName());
        } else {
            tell(to, "tpa.incoming-here", "player", from.getName());
        }
        tell(to, "tpa.hint", "player", from.getName());
    }

    private void startWarmup(Player mover, Player dest, UUID requester) {
        int warmupTime = plugin.delays().warmup(mover, "tpa", warmupSeconds);
        Player requesterPlayer = Bukkit.getPlayer(requester);
        int cooldownTime = requesterPlayer != null
                ? plugin.delays().cooldown(requesterPlayer, "tpa", cooldownSeconds)
                : cooldownSeconds;
        cancelWarmup(mover, null);
        if (warmupTime <= 0) {
            finish(new Warmup(mover.getUniqueId(), dest.getUniqueId(), requester, cooldownTime));
            return;
        }
        Warmup warmup = new Warmup(mover.getUniqueId(), dest.getUniqueId(), requester, cooldownTime);
        warmups.put(mover.getUniqueId(), warmup);
        tell(mover, "tpa.warmup", "seconds", warmupTime);
        warmup.task = mover.getScheduler().runDelayed(plugin, task -> {
            if (warmups.remove(mover.getUniqueId(), warmup)) {
                finish(warmup);
            }
        }, () -> warmups.remove(mover.getUniqueId(), warmup), warmupTime * 20L);
    }

    private void finish(Warmup warmup) {
        Player mover = Bukkit.getPlayer(warmup.mover);
        Player dest = Bukkit.getPlayer(warmup.dest);
        if (mover == null || !mover.isOnline()) {
            return;
        }
        if (dest == null || !dest.isOnline()) {
            tell(mover, "tpa.offline");
            return;
        }
        dest.getScheduler().run(plugin, task -> {
            if (!dest.isOnline() || !mover.isOnline()) {
                if (mover.isOnline()) {
                    tell(mover, "tpa.offline");
                }
                return;
            }
            Location to = dest.getLocation().clone();
            mover.teleportAsync(to, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    markCooldown(warmup.requester, warmup.cooldownSeconds);
                    tell(mover, "tpa.done");
                } else {
                    tell(mover, "tpa.failed");
                }
            });
        }, null);
    }

    private void expire(Request request) {
        if (!dropRequest(request)) {
            return;
        }
        Player sender = Bukkit.getPlayer(request.sender);
        Player target = Bukkit.getPlayer(request.target);
        if (sender != null && sender.isOnline()) {
            tell(sender, "tpa.expired", "player", request.targetName);
        }
        if (target != null && target.isOnline()) {
            tell(target, "tpa.expired-other", "player", request.senderName);
        }
    }

    private Request findIncoming(Player player, String name) {
        ConcurrentHashMap<UUID, Request> map = incoming.get(player.getUniqueId());
        if (map == null || map.isEmpty()) {
            return null;
        }
        if (name == null || name.isEmpty()) {
            Request latest = null;
            for (Request request : map.values()) {
                if (!request.alive.get()) {
                    continue;
                }
                if (latest == null || request.created > latest.created) {
                    latest = request;
                }
            }
            return latest;
        }
        Player sender = findPlayer(name);
        if (sender == null) {
            return null;
        }
        Request request = map.get(sender.getUniqueId());
        if (request == null || !request.alive.get()) {
            return null;
        }
        return request;
    }

    private boolean dropRequest(Request request) {
        if (!request.alive.compareAndSet(true, false)) {
            return false;
        }
        ScheduledTask timeout = request.timeout;
        if (timeout != null) {
            timeout.cancel();
            request.timeout = null;
        }
        outgoing.remove(request.sender, request);
        ConcurrentHashMap<UUID, Request> map = incoming.get(request.target);
        if (map != null) {
            map.remove(request.sender, request);
            if (map.isEmpty()) {
                incoming.remove(request.target, map);
            }
        }
        return true;
    }

    private void markCooldown(UUID id, int seconds) {
        if (seconds <= 0) {
            return;
        }
        cooldowns.put(id, System.currentTimeMillis() + seconds * 1000L);
    }

    private long cooldownLeft(UUID id) {
        Long until = cooldowns.get(id);
        if (until == null) {
            return 0L;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0L) {
            cooldowns.remove(id, until);
            return 0L;
        }
        return (left + 999L) / 1000L;
    }

    private Player findPlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        return Bukkit.getPlayer(name);
    }

    private void tell(Player player, String path, Object... pairs) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            plugin.lang().send(player, path, pairs);
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                plugin.lang().send(player, path, pairs);
            }
        }, null);
    }

    private static final class Request {
        private final UUID sender;
        private final UUID target;
        private final String senderName;
        private final String targetName;
        private final Kind kind;
        private final long created;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private volatile ScheduledTask timeout;

        private Request(UUID sender, UUID target, String senderName, String targetName, Kind kind) {
            this.sender = sender;
            this.target = target;
            this.senderName = senderName;
            this.targetName = targetName;
            this.kind = kind;
            this.created = System.currentTimeMillis();
        }
    }

    private static final class Warmup {
        private final UUID mover;
        private final UUID dest;
        private final UUID requester;
        private final int cooldownSeconds;
        private volatile ScheduledTask task;

        private Warmup(UUID mover, UUID dest, UUID requester, int cooldownSeconds) {
            this.mover = mover;
            this.dest = dest;
            this.requester = requester;
            this.cooldownSeconds = cooldownSeconds;
        }
    }
}
