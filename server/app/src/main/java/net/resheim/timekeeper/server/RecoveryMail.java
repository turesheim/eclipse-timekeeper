package net.resheim.timekeeper.server;

import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Vertx;
import io.vertx.ext.mail.*;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Bounded, independent delivery. Public requests never wait for mailbox/account lookup or SMTP. */
@ApplicationScoped
class RecoveryMail {
    private static final Logger LOG=Logger.getLogger(RecoveryMail.class);
    @Inject EmailRecoveryStore recovery;
    @Inject RecoveryOrigin origin;
    @Inject Vertx vertx;
    @Inject ServiceSettings settings;
    private MailClient client;
    private String sender;
    @ConfigProperty(name="timekeeper.mail.enabled",defaultValue="false") boolean enabled;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),work->{
        var thread=new Thread(work,"Timekeeper recovery mail");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());

    void initialize() {if(enabled) origin.initialize();}
    void configure(JsonNode value,String password) {
        if(client!=null) {client.close();client=null;}
        enabled=value.path("emailRecoveryEnabled").asBoolean();sender=value.path("smtpFrom").asText();
        if(!enabled) return;
        var config=new MailConfig().setHostname(value.path("smtpHost").asText()).setPort(value.path("smtpPort").asInt())
                .setSsl(value.path("smtpTls").asBoolean()).setStarttls(StartTLSOptions.valueOf(value.path("smtpStartTls").asText()))
                .setTrustAll(false).setHostnameVerificationAlgorithm("HTTPS").setConnectTimeout(5000).setSslHandshakeTimeout(5)
                .setKeepAlive(false).setMaxPoolSize(2);
        String username=value.path("smtpUsername").asText();
        if(!username.isEmpty()) config.setUsername(username).setPassword(password==null ? "" : password).setLogin(LoginOption.REQUIRED);
        else config.setLogin(LoginOption.DISABLED);
        client=MailClient.create(vertx,config);
    }
    boolean configuredEnabled() {return enabled;}
    boolean enabled() {return enabled && settings.available();}
    private void send(String address,String subject,String body) {
        try {
            if(!enabled()) return;
            client.sendMail(new MailMessage().setFrom(sender).setTo(address).setSubject(subject).setText(body))
                    .toCompletionStage().toCompletableFuture().get(10,TimeUnit.SECONDS);
        } catch(InterruptedException stopped) {Thread.currentThread().interrupt();throw new IllegalStateException("Mail delivery interrupted");}
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {throw new IllegalStateException("Mail delivery failed");}
    }
    @PreDestroy void close() {
        workers.shutdownNow();workers.getQueue().clear();
        if(client!=null) client.close();
        try {if(!workers.awaitTermination(5,TimeUnit.SECONDS)) LOG.warn("Recovery mail workers did not stop within the shutdown deadline");}
        catch(InterruptedException stopped) {Thread.currentThread().interrupt();}
    }

    boolean enqueue(Runnable work) {
        if(!enabled()) return false;
        try {workers.execute(()->{
            try {if(enabled()) work.run();}catch(RuntimeException failure) {LOG.warn("Timekeeper recovery email could not be processed; request a fresh link after checking mail configuration");}
        });return true;}
        catch(RejectedExecutionException full) {LOG.warn("Timekeeper recovery email queue is full; delivery was not scheduled");return false;}
    }

    void requestReset(String username) {enqueue(()->deliver(recovery.requestReset(username)));}
    void initialVerification(String owner) {enqueue(()->deliver(recovery.initialVerification(owner)));}
    void deliver(List<EmailRecoveryStore.Delivery> deliveries) {
        for(var delivery:deliveries) enqueue(()->{
            String route=delivery.purpose().equals("VERIFY") ? "verify-email" : "reset-password";
            String action=delivery.purpose().equals("VERIFY") ? "Verify your Timekeeper recovery email" : "Reset your Timekeeper password";
            String text=action+":\n\n"+delivery.origin()+"/web/#"+route+"/"+delivery.secret()
                    +"\n\nThis link expires after 15 minutes. Opening it does not complete the action. If you did not request this, ignore the message.\n";
            for(int attempt=0;attempt<3;attempt++) {
                if(Thread.currentThread().isInterrupted() || !recovery.deliverable(delivery)) return;
                try {send(delivery.address(),action,text);return;}
                catch(RuntimeException failure) {
                    if(attempt==2) LOG.warn("Timekeeper recovery email delivery failed after three attempts; check SMTP configuration and request a fresh link");
                }
            }
        });
    }

    void notify(List<String> addresses,String text) {
        for(String address:addresses) enqueue(()->{
            try {send(address,"Timekeeper account security",text);}
            catch(RuntimeException failure) {LOG.warn("Timekeeper account security notification could not be delivered; check SMTP configuration");}
        });
    }
}
