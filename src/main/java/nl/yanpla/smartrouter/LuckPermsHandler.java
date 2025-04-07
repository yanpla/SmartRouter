package nl.yanpla.smartrouter;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LuckPermsHandler {

    private final LuckPerms api;

    public LuckPermsHandler(LuckPerms api) {
        this.api = api;
    }

    public CompletableFuture<String> getLastServerName(UUID uuid) {
        UserManager userManager = api.getUserManager();
        return userManager.loadUser(uuid).thenApply(user -> {
            if (user != null) {
                MetaNode lastServerNode = user.getNodes().stream()
                        .filter(NodeType.META::matches)
                        .map(NodeType.META::cast)
                        .filter(n -> n.getMetaKey().equals("last-server"))
                        .findFirst().orElse(null);

                if (lastServerNode != null) {
                    return lastServerNode.getMetaValue();
                }

                return null;
            }

            return null;
        });
    }

    public void setLastServerName(UUID uuid, String serverName) {
        api.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
            if (user != null) {
                // Find last server
                MetaNode serverNode = user.getNodes().stream()
                        .filter(NodeType.META::matches)
                        .map(NodeType.META::cast)
                        .filter(n -> n.getMetaKey().equals("last-server"))
                        .findFirst().orElse(MetaNode.builder("last-server", serverName).build());

                user.data().remove(serverNode);
                serverNode = serverNode.toBuilder().value(serverName).build();
                user.data().add(serverNode);

                // Save changes
                api.getUserManager().saveUser(user);
            }
        });
    }
}