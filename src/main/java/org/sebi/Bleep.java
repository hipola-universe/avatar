package org.sebi;

public class Bleep {
    
    private String avatar;

    private String message;

    private String imageURL;

    public Bleep() {
    }

    public Bleep(String avatar, String message) {
        this.avatar = avatar;
        this.message = message;
    }
    
    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getImageURL() {
        return imageURL;
    }

    public void setImageURL(String imageURL) {
        this.imageURL = imageURL;
    }

}
