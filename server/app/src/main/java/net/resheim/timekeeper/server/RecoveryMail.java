package net.resheim.timekeeper.server;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
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
    @Inject ReactiveMailer mailer;
    @ConfigProperty(name="timekeeper.mail.enabled",defaultValue="false") boolean enabled;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),work->{
        var thread=new Thread(work,"Timekeeper recovery mail");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());

    void initialize() {if(enabled) origin.initialize();}
    boolean enabled() {return enabled;}
    @PreDestroy void close() {workers.shutdownNow();workers.getQueue().clear();}

    boolean enqueue(Runnable work) {
        if(!enabled) return false;
        try {workers.execute(()->{
            try {work.run();}catch(RuntimeException failure) {LOG.warn("Timekeeper recovery email could not be processed; request a fresh link after checking mail configuration");}
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
                try {mailer.send(Mail.withText(delivery.address(),action,text)).await().atMost(Duration.ofSeconds(10));return;}
                catch(RuntimeException failure) {
                    if(attempt==2) LOG.warn("Timekeeper recovery email delivery failed after three attempts; check SMTP configuration and request a fresh link");
                }
            }
        });
    }

    void notify(List<String> addresses,String text) {
        for(String address:addresses) enqueue(()->{
            try {mailer.send(Mail.withText(address,"Timekeeper account security",text)).await().atMost(Duration.ofSeconds(10));}
            catch(RuntimeException failure) {LOG.warn("Timekeeper account security notification could not be delivered; check SMTP configuration");}
        });
    }
}
