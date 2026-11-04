package sh.reception

/** Marks calls that exist only for Reception's own test apps. */
@RequiresOptIn(message = "Only for Reception's own test apps.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class ReceptionInternalApi
