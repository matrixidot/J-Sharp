package ann;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Kept in the class file but not visible to reflection (the default retention). */
@Retention(RetentionPolicy.CLASS)
public @interface Marker {}
