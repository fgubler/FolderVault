package ch.abwesend.foldervault.domain

import ch.abwesend.foldervault.domain.model.RetentionPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RetentionPolicyTest : StringSpec({

    "KeepLastN with count >= 1 is valid" {
        RetentionPolicy.KeepLastN(1).count shouldBe 1
        RetentionPolicy.KeepLastN(10).count shouldBe 10
    }

    "KeepLastN with count 0 throws IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            RetentionPolicy.KeepLastN(0)
        }
    }

    "KeepLastN with negative count throws IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            RetentionPolicy.KeepLastN(-5)
        }
    }
})
