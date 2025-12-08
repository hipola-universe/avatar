package org.sebi;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@RegisterAiService(modelName = "vanillaopenai")
public interface ImageBot {

    @SystemMessage("Based on the text, generate an image that enrich their social media post. The general context is that the user is living on a small cosy planet called Hipola. ")
    Image generateImage(@UserMessage String text);
    
}
