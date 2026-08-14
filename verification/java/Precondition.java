import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method precondition: a Lean expression over the parameters, assumed on entry. Replaces the
 * opening {@code Verify.requires("...")} call. Optional -- a method with no @Precondition is
 * verified under {@code True}, which is sound: a missing assumption only makes the proof harder,
 * never a false theorem provable.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Precondition {
  String value();
}
