package com.example.projectpam.model

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.projectpam.user.AppUser
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {
    private val firebaseAuth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    var loginMethod: String? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private lateinit var googleAuthClient: GoogleAuthClient

    init {
        checkCurrentUser()
    }


    private fun checkCurrentUser() {
        val currentUser = firebaseAuth.currentUser
        if (currentUser != null) {
            fetchUserData(currentUser.uid)
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    fun login(email: String, password: String) {
        _authState.value = AuthState.Loading
        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    loginMethod = "email"
                    val user = firebaseAuth.currentUser
                    user?.let { fetchUserData(it.uid)
                    }
                } else {
                    _authState.value = AuthState.Error(
                        task.exception?.localizedMessage ?: "Login failed"
                    )
                }
            }
    }

    fun signup(
        name: String,
        username: String,
        email: String,
        password: String
    ) {
        _authState.value = AuthState.Loading

        // Validasi input
        if (!isInputValid(name, username, email)) return

        // Cek ketersediaan username
        firestore.collection("users")
            .whereEqualTo("username", username)
            .get()
            .addOnSuccessListener { usernameCheck ->
                if (usernameCheck.isEmpty) {
                    createUserAccount(name, username, email, password)
                } else {
                    _authState.value = AuthState.Error("Username sudah digunakan")
                }
            }
            .addOnFailureListener { e ->
                _authState.value = AuthState.Error(
                    e.localizedMessage ?: "Gagal memeriksa username"
                )
            }
    }

    private fun isInputValid(
        name: String,
        username: String,
        email: String
    ): Boolean {
        return when {
            name.isBlank() || name.length < 1 -> {
                _authState.value = AuthState.Error("Nama tidak boleh kosong")
                false
            }
            username.isBlank() || username.length < 1 -> {
                _authState.value = AuthState.Error("Username tidak boleh kosong")
                false
            }
            email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                _authState.value = AuthState.Error("Email tidak valid")
                false
            }
            else -> true
        }
    }

    private fun createUserAccount(
        name: String,
        username: String,
        email: String,
        password: String
    ) {
        Log.d("AuthViewModel", "Starting createUserAccount")
        Log.d("AuthViewModel", "Email: $email")

        firebaseAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("AuthViewModel", "Firebase Auth - User creation successful")

                    val user = task.result?.user
                    if (user != null) {
                        Log.d("AuthViewModel", "User UID: ${user.uid}")

                        // Tambahkan log sebelum save to Firestore
                        Log.d("AuthViewModel", "Preparing to save user to Firestore")

                        // Sebelum menyimpan, cek koneksi dan autentikasi
                        user.getIdToken(true)
                            .addOnSuccessListener { tokenResult ->
                                Log.d("AuthViewModel", "ID Token retrieved successfully")

                                // Log detail token (berhati-hati dengan informasi sensitif)
                                Log.d("AuthViewModel", "Token claims: ${tokenResult.claims}")

                                saveUserToFirestore(user.uid, name, username, email)
                            }
                            .addOnFailureListener { e ->
                                Log.e("AuthViewModel", "Failed to get ID token", e)
                                _authState.value = AuthState.Error("Token error: ${e.localizedMessage}")
                            }
                    } else {
                        Log.e("AuthViewModel", "User object is null after successful creation")
                        _authState.value = AuthState.Error("Gagal membuat akun - user null")
                    }
                } else {
                    // Log detail error autentikasi
                    val exception = task.exception
                    Log.e("AuthViewModel", "Firebase Auth Error", exception)

                    // Logging detail error
                    when (exception) {
                        is FirebaseAuthWeakPasswordException ->
                            Log.e("AuthViewModel", "Weak password error")
                        is FirebaseAuthInvalidCredentialsException ->
                            Log.e("AuthViewModel", "Invalid credentials")
                        is FirebaseAuthUserCollisionException ->
                            Log.e("AuthViewModel", "User already exists")
                        else ->
                            Log.e("AuthViewModel", "Unknown auth error")
                    }

                    val errorMessage = exception?.localizedMessage ?: "Pendaftaran gagal"
                    _authState.value = AuthState.Error(errorMessage)
                }
            }
    }

    private fun saveUserToFirestore(
        userId: String,
        name: String,
        username: String,
        email: String
    ) {
        Log.d("AuthViewModel", "Saving to Firestore - UserID: $userId")

        val userMap = mapOf(
            "userId" to userId,
            "name" to name,
            "username" to username,
            "email" to email,
            "gender" to "",
            "phoneNumber" to "",
            "profilePhotoUrl" to ""
        )

        firestore.collection("users").document(userId)
            .set(userMap)
            .addOnSuccessListener {
                Log.d("AuthViewModel", "User saved to Firestore successfully")
                fetchUserData(userId)
            }
            .addOnFailureListener { exception ->
                Log.e("AuthViewModel", "Firestore save error", exception)

                // Log spesifik error Firestore
                Log.e("AuthViewModel", "Firestore Error Details: ${exception.localizedMessage}")

                _authState.value = AuthState.Error(
                    "Gagal menyimpan data: ${exception.localizedMessage}"
                )
            }
    }




    private fun fetchUserData(userId: String) {
        _authState.value = AuthState.Loading
        firestore.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val user = AppUser(
                        userId = userId,
                        name = document.getString("name") ?: "",
                        username = document.getString("username") ?: "",
                        email = document.getString("email") ?: "",
                        gender = document.getString("gender") ?: "",
                        phoneNumber = document.getString("phoneNumber") ?: "",
                        profilePhotoUrl = document.getString("profilePhotoUrl") ?: ""
                    )
                    _authState.value = AuthState.Authenticated(user)
                } else {
                    // If user doesn't exist in Firestore, create a new user document
                    val firebaseUser = firebaseAuth.currentUser
                    firebaseUser?.let { fbUser ->
                        val userMap = mapOf(
                            "userId" to fbUser.uid,
                            "name" to (fbUser.displayName ?: ""),
                            "email" to (fbUser.email ?: ""),
                            "username" to (fbUser.email?.split("@")?.get(0) ?: ""),
                            "gender" to "",
                            "phoneNumber" to "",
                            "profilePhotoUrl" to (fbUser.photoUrl?.toString() ?: "")
                        )

                        firestore.collection("users").document(fbUser.uid)
                            .set(userMap)
                            .addOnSuccessListener {
                                fetchUserData(fbUser.uid)
                            }
                            .addOnFailureListener { exception ->
                                _authState.value = AuthState.Error(
                                    "Gagal menyimpan data: ${exception.localizedMessage}"
                                )
                            }
                    } ?: run {
                        _authState.value = AuthState.Error("User data not found")
                    }
                }
            }
            .addOnFailureListener { e ->
                _authState.value =
                    AuthState.Error(e.localizedMessage ?: "Failed to fetch user data")
            }
    }
    fun signout() {
        firebaseAuth.signOut()
        loginMethod = null
        _authState.value = AuthState.Unauthenticated
    }

    // Tambahkan fungsi untuk inisialisasi Google Sign In
    fun initGoogleSignIn(context: Context) {
        googleAuthClient = GoogleAuthClient(context) // Initialize with the context
    }

    // Fungsi untuk mendapatkan intent Google Sign In
    fun getGoogleSignInIntent(): Intent {
        return googleAuthClient.getSignInIntent()
    }

    // Fungsi untuk menangani hasil Google Sign In
    suspend fun signInWithGoogle() {
        _authState.value = AuthState.Loading

        try {
            val signInSuccess = googleAuthClient.signin()
            if (signInSuccess) {
                loginMethod = "google"
                val user = firebaseAuth.currentUser
                user?.let {
                    // Cek apakah user sudah ada di Firestore
                    fetchUserData(it.uid)
                } ?: run {
                    _authState.value = AuthState.Error("User not found after Google Sign In")
                }
            } else {
                _authState.value = AuthState.Error("Google Sign In failed")
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error(
                e.localizedMessage ?: "Google Sign In failed"
            )
        }
    }

    suspend fun signoutGoogle() {
        googleAuthClient.signout()
        loginMethod = null
        _authState.value = AuthState.Unauthenticated
    }


    fun saveUserDataToFirestore(
        userId: String, // ID pengguna dari auth state
        name: String,
        username: String,
        gender: String,
        email: String,
        phoneNumber: String,
        profilePhotoUrl: String
    ) {
        val db = FirebaseFirestore.getInstance()

        // Data yang akan diperbarui
        val userData = mapOf(
            "name" to name,
            "username" to username,
            "gender" to gender,
            "email" to email,
            "phoneNumber" to phoneNumber,
            "profilePhotoUrl" to profilePhotoUrl
        )

        // Update data di Firestore
        db.collection("users").document(userId)
            .set(userData, SetOptions.merge())
            .addOnSuccessListener {
                Log.d("Firestore", "User data updated successfully")
            }
            .addOnFailureListener { e ->
                Log.e("Firestore", "Error updating user data", e)
            }
    }



}

// State autentikasi
sealed class AuthState {
    object Unauthenticated : AuthState()
    object Loading : AuthState()
    data class Authenticated(val user: AppUser) : AuthState()
    data class Error(val message: String) : AuthState()
    data class Uploading(val progress: Int) : AuthState() // Progress bar
}