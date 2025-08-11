package com.alphine.mysticWorlds.economy;

import net.milkbowl.vault.economy.Economy;
import net.thenextlvl.service.api.economy.Account;
import net.thenextlvl.service.api.economy.EconomyController;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class EconomyBridge {
    private EconomyController serviceIO; // null if missing
    private Economy vault;               // null if missing

    public void init(Plugin plugin) {
        try {
            serviceIO = Bukkit.getServicesManager().load(EconomyController.class);
            if (serviceIO != null) plugin.getLogger().info("[MysticWorlds] ServiceIO EconomyController loaded.");
        } catch (Throwable ignored) {}

        if (serviceIO == null) {
            var reg = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (reg != null) {
                vault = reg.getProvider();
                plugin.getLogger().info("[MysticWorlds] Vault economy loaded (fallback).");
            }
        }
    }

    public boolean isAvailable() {
        return serviceIO != null || vault != null;
    }

    /* ------------------------ ServiceIO helpers ------------------------ */

    private CompletableFuture<Optional<Account>> getOrLoadAccount(UUID uuid) {
        var cached = serviceIO.getAccount(uuid);
        if (cached.isPresent()) return CompletableFuture.completedFuture(cached);

        return serviceIO.tryGetAccount(uuid)
                .thenCompose(opt -> opt.isPresent()
                        ? CompletableFuture.completedFuture(opt)
                        : serviceIO.createAccount(uuid).thenApply(Optional::of));
    }

    /* ------------------------ Public API ------------------------ */

    public CompletableFuture<Boolean> deposit(Player player, double amount) {
        if (serviceIO != null) {
            BigDecimal bd = BigDecimal.valueOf(amount);
            return getOrLoadAccount(player.getUniqueId())
                    .thenApply(opt -> opt.map(acc -> {
                        acc.deposit(bd); // returns new balance (BigDecimal), we just treat success as true
                        return true;
                    }).orElse(false));
        }
        if (vault != null) {
            boolean ok = vault.depositPlayer(player, amount).transactionSuccess();
            return CompletableFuture.completedFuture(ok);
        }
        return CompletableFuture.completedFuture(false);
    }

    public CompletableFuture<Boolean> withdraw(Player player, double amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(true);

        if (serviceIO != null) {
            BigDecimal bd = BigDecimal.valueOf(amount);
            return getOrLoadAccount(player.getUniqueId())
                    .thenApply(opt -> opt.map(acc -> {
                        // BLOCK if not enough funds
                        if (acc.getBalance().compareTo(bd) < 0) return false;
                        acc.withdraw(bd);
                        return true;
                    }).orElse(false));
        }
        if (vault != null) {
            boolean ok = vault.withdrawPlayer(player, amount).transactionSuccess();
            return CompletableFuture.completedFuture(ok);
        }
        return CompletableFuture.completedFuture(false);
    }

    /** BigDecimal balance (native to ServiceIO). */
    public CompletableFuture<BigDecimal> getBalanceBig(Player player) {
        if (serviceIO != null) {
            return getOrLoadAccount(player.getUniqueId())
                    .thenApply(opt -> opt.map(Account::getBalance).orElse(BigDecimal.ZERO));
        }
        if (vault != null) {
            return CompletableFuture.completedFuture(BigDecimal.valueOf(vault.getBalance(player)));
        }
        return CompletableFuture.completedFuture(BigDecimal.ZERO);
    }

    /** Convenience: double balance. */
    public CompletableFuture<Double> getBalance(Player player) {
        return getBalanceBig(player).thenApply(BigDecimal::doubleValue);
    }
}
