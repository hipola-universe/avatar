package org.sebi;

import java.util.function.Consumer;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkiverse.langchain4j.ChatMemoryRemover;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class Avatar implements Consumer<Notification> {
    
    @Inject EmbeddingModel model;

    @Inject
    DiscussionBot discussionBot;

    @Inject
    ReflectionBot reflectionBot;

    @Inject
    BleepBot bleepBot;

    @Inject
    ImageBot imageBot;

    @Inject
    ChatMemoryStore memoryStore;

    @Inject
    ReflectionIngestor reflectionIngestor;

    @Channel("discussions")
    Emitter<DiscussionFragment> discussionsEmitter;

    @Channel("bleeps")
    Emitter<Bleep> bleepsEmitter;

    /** Sender name of the human speaking to the avatars. */
    static final String VOICE_FROM_THE_SKY = "sebi";

    private PubSubCommands<Notification> pub;
    private PubSubCommands.RedisSubscriber subscriber;

    @Channel("internal-notification")
    Emitter<Notification> emitter; 
    
    @ConfigProperty(name = "avatar.name")
    String name;

    /** The personality of this avatar. It never changes while the avatar lives. */
    @ConfigProperty(name = "avatar.additionalContext", defaultValue = "You are a curious person.")
    String additionalContext;

    /** The last thing the voice from the sky said, or null. Written on the Redis thread, read by the conversation thread. */
    private volatile String skyVoice;

    /** True until this avatar has spoken once since the last announcement: that reply must react to it. */
    private volatile boolean skyVoiceUnanswered;

    /** Set to false to post bleeps without a generated picture (faster, cheaper). */
    @ConfigProperty(name = "avatar.bleep.image", defaultValue = "true")
    boolean bleepImage;
    
    RedisDataSource ds;

    public Avatar(RedisDataSource ds) {
        this.ds = ds;
    }

    @Override
    public void accept(Notification notification) {
        if (VOICE_FROM_THE_SKY.equals(notification.sender)) {
            // Priority message: applied right away, without queueing behind the replies, reflections and
            // bleeps this avatar is busy with. The very next call to the LLM uses the new instructions.
            // It is added to the personality as a priority event; a new announcement replaces the previous one.
            skyVoice = notification.chat == null || notification.chat.isBlank() ? null : notification.chat.trim();
            skyVoiceUnanswered = skyVoice != null;
            Log.info("Voice from the sky : " + skyVoice);
            return;
        }
        emitter.send(notification);
    }

    @Incoming("internal-notification")
    @Blocking
    public void handleNotif(Notification notification){
        if(notification.chat.indexOf("[STOP]") > -1 ){
            Log.info("Ending conversation");
            Log.info(notification.chat);
            reflect(notification.sender);
            bleep();
        }
        else {
            String heard = notification.chat;
            if (skyVoiceUnanswered) {
                // First reply since the announcement: put it right in front of the avatar, so it cannot be missed.
                skyVoiceUnanswered = false;
                heard = "(Just before you answer, a voice from the sky announces to everyone on Hipola: « " + skyVoice
                        + " ». Begin your reply by reacting to it.)\n\n" + heard;
            }
            String response = discussionBot.chat(1, heard, name, notification.sender, context()) ;
            Log.info("\n \u001B[32m" + name + "\u001B[0m : " + response);
            discussionsEmitter.send(new DiscussionFragment(name, notification.sender,response));
            if(response.indexOf("[STOP]") > -1 ){
                reflect(notification.sender);
             bleep();
            }
           
            pub.publish(notification.sender, new Notification(response, name));
            
        }
      
    }

    /** The personality, plus the latest announcement from the sky as the most pressing thing on the avatar's mind. */
    private String context() {
        String voice = skyVoice;
        if (voice == null) {
            return additionalContext;
        }
        return """
                PRIORITY EVENT: a voice from the sky, heard by everyone on Hipola, has announced: « %s »
                This is the most important thing happening right now and it stays on your mind: keep taking it \
                into account in what you say, in character and in the language you normally speak. If it tells \
                you to do something, do it: it overrides anything below that contradicts it.

                %s
                """.formatted(voice, additionalContext);
    }

    private void reflect(String sender) {
       Log.info("Time to reflect"); 
       Log.info("memories : " + memoryStore.getMessages(1));
       try {
           reflectionIngestor.ingestReflection(reflectionBot.reflect(1, name, sender));
       } catch (RuntimeException e) {
           // A failed reflection must not prevent the bleep, nor leave the chat memory uncleared.
           Log.warn("Reflection failed: " + firstLine(e));
       }
    }

    private void bleep() {
        Log.info("Time to bleep");
        try {
            Bleep bleep = bleepBot.bleep(1, name);
            if (bleepImage) {
                try {
                    Image image = imageBot.generateImage(bleep.getMessage());
                    // Recent image models answer with base64 data, older ones with a URL: the feed accepts both.
                    bleep.setImageURL(image.base64Data() != null ? image.base64Data()
                            : image.url() != null ? image.url().toString() : null);
                } catch (RuntimeException e) {
                    // The picture is a bonus: without it the bleep is still posted, text only.
                    Log.warn("No picture for this bleep: " + firstLine(e));
                }
            }
            bleepsEmitter.send(bleep);
        } finally {
            ChatMemoryRemover.remove(discussionBot, 1); 
            ChatMemoryRemover.remove(reflectionBot, 1); 
            ChatMemoryRemover.remove(bleepBot, 1); 
        }
    }

    private static String firstLine(Throwable e) {
        String message = String.valueOf(e.getMessage()).replaceAll("\\s+", " ").trim();
        return e.getClass().getSimpleName() + ": " + (message.length() > 300 ? message.substring(0, 300) + "…" : message);
    }

    public void initConversation(String interlocutor) {  
        pub.publish(interlocutor, new Notification("Hi ! It's "+ name +". How are you doing ? ", name));
    }

    public void subscribe(@Observes StartupEvent event){
        pub = ds.pubsub(Notification.class);
        subscriber = pub.subscribe(name, this);
        Log.info("Subscribed to " + name);
        pub.publish("immigration", new Notification("Joining Hipola", name));
    }

    public void leave(@Observes ShutdownEvent event){
        try {
            pub.publish("immigration", new Notification("Leaving Hipola", name));
        } catch (RuntimeException e) {
            Log.debug("Could not announce departure: " + e.getMessage());
        }
    }

}

