package net.resheim.timekeeper.server;

import jakarta.enterprise.context.ApplicationScoped;

/** Fixed public provider endpoints; tests can replace this CDI boundary without changing production hosts. */
@ApplicationScoped
class TaskProviderClients {
    TaskCloudProvider cloud(TaskCloudProvider.Provider provider){return new TaskCloudProvider(provider);}
    TaskProviderOAuth oauth(TaskCloudProvider.Provider provider){return new TaskProviderOAuth(provider);}
}
