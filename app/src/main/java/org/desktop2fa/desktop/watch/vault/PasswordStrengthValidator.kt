package org.desktop2fa.desktop.watch.vault

data class PasswordStrengthResult(
    val isValid: Boolean,
    val score: Int, // 0 to 4
    val feedback: String
)

object PasswordStrengthValidator {
    fun evaluate(password: String): PasswordStrengthResult {
        if (password.length < 8) {
            return PasswordStrengthResult(false, 0, "Password must be at least 8 characters long.")
        }

        var hasUpper = false
        var hasLower = false
        var hasDigit = false
        var hasSpecial = false

        for (char in password) {
            when {
                char.isUpperCase() -> hasUpper = true
                char.isLowerCase() -> hasLower = true
                char.isDigit() -> hasDigit = true
                !char.isLetterOrDigit() -> hasSpecial = true
            }
        }

        var score = 1
        if (hasUpper && hasLower) score++
        if (hasDigit) score++
        if (hasSpecial) score++
        if (password.length >= 12) score++

        // Require score >= 3 matching desktop-2fa security policy
        val isValid = password.length >= 8 && score >= 3

        val feedback = when {
            score <= 2 -> "Weak password. Add uppercase, numbers, and symbols."
            score == 3 -> "Moderate password."
            else -> "Strong password."
        }

        return PasswordStrengthResult(isValid, score.coerceIn(0, 4), feedback)
    }
}
