package com.enonic.plugin;


import com.enonic.cms.api.client.ClientFactory;
import com.enonic.cms.api.client.RemoteClient;
import com.enonic.cms.api.plugin.PluginEnvironment;
import com.enonic.plugin.util.ResponseMessage;
import com.google.common.base.Strings;

public class ClientProvider {

    PluginEnvironment pluginEnvironment;

    public ClientProvider(PluginEnvironment pluginEnvironment) {
        this.pluginEnvironment = pluginEnvironment;
    }

    public RemoteClient getSourceserverClient() {
        if (Strings.isNullOrEmpty((String) pluginEnvironment.getSharedObject("context_sourceserverUrl"))) {
            return null;
        }
        RemoteClient remoteClient = ClientFactory.getRemoteClient((String) pluginEnvironment.getSharedObject("context_sourceserverUrl"));
        try {
            remoteClient.logout();
            String sourceserverUsername = (String) pluginEnvironment.getSharedObject("context_sourceserverUsername");
            String sourceserverPassword = (String) pluginEnvironment.getSharedObject("context_sourceserverPassword");
            remoteClient.login(sourceserverUsername, sourceserverPassword);
        } catch (Exception e) {
            ResponseMessage.addWarningMessage("Exception when getting remote client. " + e);
        }
        return remoteClient;
    }

    public RemoteClient getTargetserverClient() {
        if (Strings.isNullOrEmpty((String) pluginEnvironment.getSharedObject("context_targetserverUrl"))) {
            return null;
        }
        RemoteClient localClient = ClientFactory.getRemoteClient((String) pluginEnvironment.getSharedObject("context_targetserverUrl"));
        try {
            localClient.logout();
            String targetserverUsername = (String) pluginEnvironment.getSharedObject("context_targetserverUsername");
            String targetserverPassword = (String) pluginEnvironment.getSharedObject("context_targetserverPassword");
            localClient.login(targetserverUsername, targetserverPassword);
        } catch (Exception e) {
            ResponseMessage.addWarningMessage("Exception when getting remote client. " + e);
        }
        return localClient;
    }

    public PluginEnvironment getPluginEnvironment() {
        return pluginEnvironment;
    }
}
