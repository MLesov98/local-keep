package com.localkeep.core.import

/**
 * A rejection this app authored deliberately, carrying an [ImportFailureReason] rather than a
 * message, so it is always safe to hand to the UI — see [ImportFailureReason]'s kdoc for the full
 * "why a typed reason and not a string" reasoning.
 *
 * Importers parse untrusted files, and their catch-alls used to read
 * `AppError.Validation(t.message ?: "failed to parse ...")`. That reads as "prefer the specific
 * message", but it cannot distinguish the two kinds of throwable that reach it:
 *
 *  - the ones this code threw on purpose, whose meaning was chosen to be shown to a person; and
 *  - anything else, whose text was written by a library about its own internals and may quote the
 *    file's contents, a class name, or a path.
 *
 * Only the first kind may reach the UI, and now it does so as a closed enum rather than free text —
 * the rule is never echo `e.message`, paths, or URIs, and a typed reason cannot carry any of
 * those even by accident. [KdbxImporter.describeFailure] uses this same type to label a caught
 * throwable without throwing it, for the identical reason: every error this module returns must
 * carry a reason it authored, never a message a library wrote.
 *
 * Public (not `internal`): [ImportWizardViewModel] in `feature/import` reads [reason] back off
 * `AppError.cause` to resolve it to a `@StringRes`, and `internal` visibility is module-scoped in
 * Kotlin — invisible across the `core/import` / `feature/import` module boundary.
 */
class ImportRejection(val reason: ImportFailureReason, cause: Throwable? = null) :
    IllegalArgumentException(reason.toString(), cause)
