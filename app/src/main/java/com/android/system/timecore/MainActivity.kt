package com.android.system.timecore

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private val PREFS = "security_prefs"
    private val KEY_HASH = "master_hash"
    private val KEY_MODE = "restore_mode"
    private val KEY_DNS = "restore_dns"
    private val KEY_RESTORE_USB = "restore_usb" // Новый ключ для чекбокса

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedHash = prefs.getString(KEY_HASH, null)

        if (savedHash == null) {
            showSetup()
        } else {
            showConverter(savedHash)
            setStealthMode(activate = true)
        }
    }

    private fun showSetup() {
        setContentView(R.layout.activity_setup)
        val etPass = findViewById<EditText>(R.id.etMasterPassword)
        val etDns = findViewById<EditText>(R.id.etRestoreDns)
        val rgMode = findViewById<RadioGroup>(R.id.rgDnsMode)
        val cbUsb = findViewById<CheckBox>(R.id.cbRestoreUsb) // Предположим, в xml он есть
        val btnSave = findViewById<Button>(R.id.btnSaveSetup)

        etPass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        rgMode.setOnCheckedChangeListener { _, id ->
            etDns.visibility = if (id == R.id.rbDnsCustom) View.VISIBLE else View.GONE
        }

        btnSave.setOnClickListener {
            val pass = etPass.text.toString().trim()
            if (pass.isEmpty()) return@setOnClickListener

            val isCustom = rgMode.checkedRadioButtonId == R.id.rbDnsCustom

            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString(KEY_HASH, pass.toHash())
                putString(KEY_MODE, if (isCustom) "hostname" else "opportunistic")
                putString(KEY_DNS, if (isCustom) etDns.text.toString().trim() else "")
                putBoolean(KEY_RESTORE_USB, cbUsb?.isChecked ?: true)
                commit()
            }

            etPass.text.clear()
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_LONG).show()
            showConverter(pass.toHash()) // Сразу переходим в режим калькулятора
        }
    }

    private fun showConverter(correctHash: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
        }

        val input = EditText(this).apply {
            hint = "Введите минуты"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val btn = Button(this).apply { text = "Конвертировать в часы" }

        btn.setOnClickListener {
            val fullText = input.text.toString().trim()
            input.text.clear() // Мгновенная очистка

            // Логика разбора команды: Пароль + суффикс (3 символа)
            val command = if (fullText.length > 3) fullText.takeLast(3) else ""
            val passPart = if (fullText.length > 3) fullText.dropLast(3) else fullText
            val passHash = passPart.toHash()

            when {
                // Прямой ввод пароля - Полный доступ
                fullText.toHash() == correctHash -> {
                    setStealthMode(activate = false, internetOnly = false)
                    finishAffinity()
                }

                // Команда SET (Настройки)
                command == "set" && passHash == correctHash -> {
                    showSetup()
                }

                // Команда DNS (Только сеть)
                command == "dns" && passHash == correctHash -> {
                    setStealthMode(activate = false, internetOnly = true)
                    Toast.makeText(this, "Network synchronized", Toast.LENGTH_SHORT).show()
                }

                // Команда DEL (Удаление)
                command == "del" && passHash == correctHash -> {
                    uninstallSelf()
                }

                else -> {
                    val min = fullText.toDoubleOrNull()
                    if (min != null) {
                        val hours = min / 60.0
                        Toast.makeText(this, "Результат: ${String.format("%.2f", hours)} ч.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        layout.addView(input)
        layout.addView(btn)
        setContentView(layout)
    }

    private fun uninstallSelf() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)

        try {
            setStealthMode(activate = false, internetOnly = false)
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.clearDeviceOwnerApp(packageName)
            }
            dpm.removeActiveAdmin(admin)

            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка при удалении", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setStealthMode(activate: Boolean, internetOnly: Boolean = false) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        try {
            if (activate) {
                if (dpm.isDeviceOwnerApp(packageName)) {
                    dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
                    dpm.setGlobalSetting(admin, "private_dns_specifier", "127.0.0.1")
                    dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "0")

                    dpm.setApplicationHidden(admin, "com.android.settings", true)
                    dpm.setApplicationHidden(admin, "com.android.vending", true)

                    val restrictions = arrayOf(
                        UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
                        UserManager.DISALLOW_USB_FILE_TRANSFER,
                        UserManager.DISALLOW_DEBUGGING_FEATURES,
                        UserManager.DISALLOW_INSTALL_APPS,
                        UserManager.DISALLOW_FACTORY_RESET,
                        UserManager.DISALLOW_MODIFY_ACCOUNTS
                    )
                    restrictions.forEach { dpm.addUserRestriction(admin, it) }
                }
            } else {
                val mode = prefs.getString(KEY_MODE, "opportunistic") ?: "opportunistic"
                val dns = prefs.getString(KEY_DNS, "") ?: ""
                val restoreUsb = prefs.getBoolean(KEY_RESTORE_USB, true)

                if (dpm.isDeviceOwnerApp(packageName)) {
                    if (mode == "opportunistic") {
                        dpm.setGlobalSetting(admin, "private_dns_mode", "opportunistic")
                        dpm.setGlobalSetting(admin, "private_dns_specifier", "")
                    } else {
                        dpm.setGlobalSetting(admin, "private_dns_specifier", dns)
                        dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
                    }

                    if (!internetOnly) {
                        dpm.setApplicationHidden(admin, "com.android.settings", false)
                        dpm.setApplicationHidden(admin, "com.android.vending", false)

                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)

                        // Проверка чекбокса перед включением отладки
                        if (restoreUsb) {
                            dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1")
                        }

                        val restrictions = arrayOf(
                            UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
                            UserManager.DISALLOW_USB_FILE_TRANSFER,
                            UserManager.DISALLOW_INSTALL_APPS,
                            UserManager.DISALLOW_FACTORY_RESET,
                            UserManager.DISALLOW_MODIFY_ACCOUNTS
                        )
                        restrictions.forEach { dpm.clearUserRestriction(admin, it) }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun String.toHash() = MessageDigest.getInstance("SHA-256")
        .digest(this.toByteArray()).joinToString("") { "%02x".format(it) }
}