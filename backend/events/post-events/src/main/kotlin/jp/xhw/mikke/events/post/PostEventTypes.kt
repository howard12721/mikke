package jp.xhw.mikke.events.post

object PostEventTypes {
    const val CREATED = "post.created"
    const val DELETED = "post.deleted"

    // Retained so consumers can ignore events emitted before visibility became fixed.
    const val VISIBILITY_UPDATED = "post.visibility_updated"
    const val CAPTION_UPDATED = "post.caption_updated"
}
