package sh.reception

/** Marks calls that exist only for Reception's own test apps and plugins. */
@RequiresOptIn(message = "Only for Reception's own test apps and plugins.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class ReceptionInternalApi
