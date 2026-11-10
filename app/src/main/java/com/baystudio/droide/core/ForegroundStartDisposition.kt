package com.baystudio.droide.core

/** Admission closure is not retirement of established owners during durable preservation. */
internal fun stopUnpromotedOwnerlessService(foregroundPromoted: Boolean, hasActiveOwners: Boolean, hasResidentOwner: Boolean): Boolean =
    !foregroundPromoted && !hasActiveOwners && !hasResidentOwner
