package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class ForegroundStartDispositionTest {
 @Test fun freshRejectedStartStops(){assertTrue(stopUnpromotedOwnerlessService(false,false,false))}
 @Test fun promotedProtectionRetained(){assertFalse(stopUnpromotedOwnerlessService(true,false,false))}
 @Test fun activeOwnerBeforePromotionRetained(){assertFalse(stopUnpromotedOwnerlessService(false,true,false))}
 @Test fun residentOwnerBeforePromotionRetained(){assertFalse(stopUnpromotedOwnerlessService(false,false,true))}
 @Test fun allEstablishedCombinationsRetained(){for(p in listOf(false,true))for(a in listOf(false,true))for(r in listOf(false,true))if(p||a||r)assertFalse(stopUnpromotedOwnerlessService(p,a,r))}
 @Test fun repeatedFreshRequestIdempotent(){repeat(20){assertTrue(stopUnpromotedOwnerlessService(false,false,false))}}
 @Test fun successfulPromotionChangesDisposition(){assertTrue(stopUnpromotedOwnerlessService(false,false,false));assertFalse(stopUnpromotedOwnerlessService(true,false,false))}
}
