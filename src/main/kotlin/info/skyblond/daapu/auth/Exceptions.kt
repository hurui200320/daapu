package info.skyblond.daapu.auth

class UsernameExistsException(username: String) : Exception("Username '$username' already exists'")