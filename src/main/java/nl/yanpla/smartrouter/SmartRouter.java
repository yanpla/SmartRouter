package nl.yanpla.smartrouter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.inject.name.Named;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

/**
* The main plugin class.
*/
@Plugin(id = "smartrouter",
		name = "SmartRouter",
		version = "0.0.1",
		authors = { "yanpla" })
public final class SmartRouter {
	private final ProxyServer server;
	private final Logger logger;
	private final String name;
	private boolean hasLuckPerms;
	private LuckPermsHandler handler;

	private final Map<UUID, String> lastServerCache = new ConcurrentHashMap<>();

	private static YamlDocument config;

	private List<String> interceptServers;
	private List<String> rememberServers;
	private boolean queueCompatibility;
	private int queueRedirectDelay;

	@Inject
	public SmartRouter(ProxyServer server, Logger logger, @DataDirectory Path dataFolder) {
		this.server = server;
		this.logger = logger;

		try {
			config = YamlDocument.create(new File(dataFolder.toFile(), "config.yml"),
					Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
					GeneralSettings.DEFAULT,
					LoaderSettings.builder().setAutoUpdate(true).build(),
					DumperSettings.DEFAULT,
					UpdaterSettings.builder().setVersioning(new BasicVersioning("file-version"))
							.setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS).build()
			);

			config.update();
			config.save();
		} catch (IOException e) {
			logger.error("Could not create/load config.yml! this plugin will now shutdown.");
			Optional<PluginContainer> container = server.getPluginManager().getPlugin("smartrouter");
			container.ifPresent(pluginContainer -> pluginContainer.getExecutorService().shutdown());
		}

		this.name = this.getClass().getAnnotation(Plugin.class).name();

		this.onLoad();
	}

	@Inject(optional = true)
	public void initLuckPerms(@Named("luckperms") PluginContainer luckPermsContainer) {
		this.hasLuckPerms = luckPermsContainer != null;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		if (hasLuckPerms) {
			LuckPerms api = LuckPermsProvider.get();
			handler = new LuckPermsHandler(api);
			this.onEnable();
		} else {
			logger.warn("LuckPerms not found, Disabling plugin.");
			this.onDisable();
		}
	}

	@Subscribe
	public void onPlayerLogin(LoginEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		handler.getLastServerName(uuid).thenAccept(lastServerName -> {
			if (lastServerName != null) {
				lastServerCache.put(uuid, lastServerName);
			}
		});
	}

	@Subscribe
	public void onServerPreConnect(ServerPreConnectEvent event) {
		if (queueCompatibility) return;
		String target = event.getOriginalServer().getServerInfo().getName();

		if (!interceptServers.contains(target)) return;

		// if the player is already on the server, do nothing
		if (event.getPreviousServer() != null) {
			String prev = event.getPreviousServer().getServerInfo().getName();
			if (rememberServers.contains(prev)) return;
		}

		UUID uuid = event.getPlayer().getUniqueId();
		String lastServer = lastServerCache.get(uuid);
		if (lastServer != null) {
			server.getServer(lastServer).ifPresent(server -> {
				event.setResult(ServerPreConnectEvent.ServerResult.allowed(server));
			});
		}
	}

	@Subscribe
	public void onServerChange(ServerConnectedEvent event) {
		// Get the name of the server the player is connecting to.
		String current = event.getServer().getServerInfo().getName();

		// Only proceed if the current server is either a server we need to remember
		// OR a server we might intercept for redirection.
		if (!rememberServers.contains(current) && !interceptServers.contains(current)) return;

		UUID uuid = event.getPlayer().getUniqueId();

		// ---------------------------------------
		// Compatibility & Redirection Logic
		// ---------------------------------------
		// If the queue compatibility mode is enabled and the current server is in the intercept list...
		if (queueCompatibility && interceptServers.contains(current)) {
			// If there is a previous server, and it is one we remember,
			// then just cache the current server and do not redirect.
			if (event.getPreviousServer().isPresent()) {
				String prev = event.getPreviousServer().get().getServerInfo().getName();
				if (rememberServers.contains(prev)) {
					cacheServer(uuid, current);
					return;
				}
			}

			// Check if we have a previously remembered server for the player.
			String lastServer = lastServerCache.get(uuid);
			// If such a server exists, and it's different from the current server...
			if (lastServer != null && !lastServer.equals(current)) {
				// Get the target server from Velocity.
				server.getServer(lastServer).ifPresent(targetServer -> {
					try {
						// Optionally, ping the target server to ensure it is reachable.
						targetServer.ping().join();
					} catch (CancellationException | CompletionException ex) {
						logger.error("Error pinging server: {}", lastServer, ex);
					}

					// Schedule the redirection task with a delay (here 100ms)
					// to avoid interfering with the existing connection process.
					server.getScheduler().buildTask(this, () -> {
						// Fire the connection request to the target server.
						event.getPlayer().createConnectionRequest(targetServer).fireAndForget();

						// Cache the server name the player was redirected to.
						String redirectedTo = targetServer.getServerInfo().getName();
						cacheServer(uuid, redirectedTo);
					}).delay(queueRedirectDelay, TimeUnit.MILLISECONDS).schedule();
				});

				// Exit to prevent the normal caching of the intercepted server.
				return;
			}
		}

		// ---------------------------------------
		// Fallback: Cache current server if no redirection took place.
		// ---------------------------------------
		cacheServer(uuid, current);
	}

	private void cacheServer(UUID uuid, String server) {
		lastServerCache.put(uuid, server);
		handler.setLastServerName(uuid, server);
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		this.onDisable();
	}

	public void onLoad() {
		// Load config
		interceptServers = config.getStringList(Route.from("intercept-servers"));
		rememberServers = config.getStringList(Route.from("remember-servers"));
		queueCompatibility = config.getBoolean(Route.from("queue-compatibility"));
		queueRedirectDelay = config.getInt(Route.from("queue-redirect-delay"));

        logger.info("{} loaded.", this.name);
	}

	public void onEnable() {
        logger.info("{} enabled", this.name);
	}

	public void onDisable() {
        logger.info("{} disabled", this.name);
	}
}
