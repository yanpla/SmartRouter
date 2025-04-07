package nl.yanpla.smartrouter;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.inject.name.Named;
import com.velocitypowered.api.event.Subscribe;
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

	private static SmartRouter instance;

	private final ProxyServer server;
	private final Logger logger;
	private final File dataFolder;
	private final File file;
	private final String name;
	private boolean hasLuckPerms;
	private LuckPermsHandler handler;

	private static YamlDocument config;

	@Inject
	public SmartRouter(ProxyServer server, Logger logger, @DataDirectory Path dataFolder) {
		instance = this;

		this.server = server;
		this.logger = logger;
		this.dataFolder = new File(dataFolder.toFile().getParentFile(), this.getClass().getAnnotation(Plugin.class).name());

		try {
			this.file = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());

		} catch (final URISyntaxException ex) {
			throw new RuntimeException(ex);
		}

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
			getLogger().warn("LuckPerms not found, Disabling plugin.");
			this.onDisable();
		}
	}

	@Subscribe
	public void onServerPreConnect(ServerPreConnectEvent event) {
		String target = event.getOriginalServer().getServerInfo().getName();

		List<String> interceptServers = config.getStringList(Route.from("intercept-servers"));
		if (!interceptServers.contains(target)) return;

		// if the player is already on the server, do nothing
		if (event.getPreviousServer() != null) {
			String originalServer = event.getPreviousServer().getServerInfo().getName();
			List<String> rememberServers = config.getStringList(Route.from("remember-servers"));
			if (rememberServers.contains(originalServer)) {
				return;
			}
		}

		handler.getLastServerName(event.getPlayer().getUniqueId()).thenAcceptAsync(lastServerName -> {
			if (lastServerName != null) {
				getServer().getServer(lastServerName).ifPresent((registeredServer) -> {
					event.setResult(ServerPreConnectEvent.ServerResult.allowed(registeredServer));
				});
			}
		}).join();
	}

	@Subscribe
	public void onServerChange(ServerConnectedEvent serverConnectedEvent) {
		List<String> rememberServers = config.getStringList(Route.from("remember-servers"));
		String serverName = serverConnectedEvent.getServer().getServerInfo().getName();
		if (!rememberServers.contains(serverName)) return;
		handler.setLastServerName(serverConnectedEvent.getPlayer().getUniqueId(), serverName);
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		this.onDisable();
	}

	public void onLoad() {
		System.out.println(this.name + " loaded.");
	}

	public void onEnable() {
		System.out.println(this.name + " enabled");
	}

	public void onDisable() {
		System.out.println(this.name + " disabled");
	}

	public ProxyServer getServer() {
		return server;
	}

	public Logger getLogger() {
		return logger;
	}

	public File getDataFolder() {
		return dataFolder;
	}

	public File getFile() {
		return file;
	}

	public String getName() {
		return name;
	}

	public static SmartRouter getInstance() {
		return instance;
	}

	public boolean hasLuckPerms() {
		return hasLuckPerms;
	}
}
