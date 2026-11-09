package com.openyt.core.application

import com.openyt.core.domain.ShareableMedia

/**
 * Platform actions for completed media. Android shares through the system chooser
 * (FileProvider); desktop opens the file or reveals it in the file manager.
 */
interface MediaActions {
    val canShare: Boolean
    val canOpen: Boolean
    val canReveal: Boolean

    fun share(media: List<ShareableMedia>, chooserTitle: String)

    fun open(media: ShareableMedia): Boolean

    fun reveal(media: ShareableMedia): Boolean
}
