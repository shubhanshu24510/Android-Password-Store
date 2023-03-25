/*
 * Copyright © 2014-2021 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.ui.crypto.BasePgpActivity
import app.passwordstore.ui.crypto.PasswordDialog
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.autofill.AutofillResponseBuilder
import app.passwordstore.util.autofill.DirectoryStructure
import app.passwordstore.util.extensions.asLog
import com.github.androidpasswordstore.autofillparser.AutofillAction
import com.github.androidpasswordstore.autofillparser.Credentials
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.github.michaelbull.result.runCatching
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.logcat

@RequiresApi(Build.VERSION_CODES.O)
@AndroidEntryPoint
class AutofillDecryptActivity : BasePgpActivity() {

  companion object {

    private const val EXTRA_FILE_PATH = "app.passwordstore.autofill.oreo.EXTRA_FILE_PATH"
    private const val EXTRA_SEARCH_ACTION = "app.passwordstore.autofill.oreo.EXTRA_SEARCH_ACTION"

    private var decryptFileRequestCode = 1

    fun makeDecryptFileIntent(file: File, forwardedExtras: Bundle, context: Context): Intent {
      return Intent(context, AutofillDecryptActivity::class.java).apply {
        putExtras(forwardedExtras)
        putExtra(EXTRA_SEARCH_ACTION, true)
        putExtra(EXTRA_FILE_PATH, file.absolutePath)
      }
    }

    fun makeDecryptFileIntentSender(file: File, context: Context): IntentSender {
      val intent =
        Intent(context, AutofillDecryptActivity::class.java).apply {
          putExtra(EXTRA_SEARCH_ACTION, false)
          putExtra(EXTRA_FILE_PATH, file.absolutePath)
        }
      return PendingIntent.getActivity(
          context,
          decryptFileRequestCode++,
          intent,
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
          } else {
            PendingIntent.FLAG_CANCEL_CURRENT
          },
        )
        .intentSender
    }
  }

  @Inject lateinit var passwordEntryFactory: PasswordEntry.Factory

  private lateinit var directoryStructure: DirectoryStructure

  override fun onStart() {
    super.onStart()
    val filePath =
      intent?.getStringExtra(EXTRA_FILE_PATH)
        ?: run {
          logcat(ERROR) { "AutofillDecryptActivity started without EXTRA_FILE_PATH" }
          finish()
          return
        }
    val clientState =
      intent?.getBundleExtra(AutofillManager.EXTRA_CLIENT_STATE)
        ?: run {
          logcat(ERROR) { "AutofillDecryptActivity started without EXTRA_CLIENT_STATE" }
          finish()
          return
        }
    val isSearchAction = intent?.getBooleanExtra(EXTRA_SEARCH_ACTION, true)!!
    val action = if (isSearchAction) AutofillAction.Search else AutofillAction.Match
    directoryStructure = AutofillPreferences.directoryStructure(this)
    logcat { action.toString() }
    requireKeysExist {
      val dialog = PasswordDialog()
      lifecycleScope.launch {
        withContext(Dispatchers.Main) {
          dialog.password.collectLatest { value ->
            if (value != null) {
              decrypt(File(filePath), clientState, action, value)
            }
          }
        }
      }
      dialog.show(supportFragmentManager, "PASSWORD_DIALOG")
    }
  }

  private suspend fun decrypt(
    filePath: File,
    clientState: Bundle,
    action: AutofillAction,
    password: String,
  ) {
    val credentials = decryptCredential(filePath, password)
    if (credentials == null) {
      setResult(RESULT_CANCELED)
    } else {
      val fillInDataset =
        AutofillResponseBuilder.makeFillInDataset(
          this@AutofillDecryptActivity,
          credentials,
          clientState,
          action
        )
      withContext(Dispatchers.Main) {
        setResult(
          RESULT_OK,
          Intent().apply { putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillInDataset) }
        )
      }
    }
    withContext(Dispatchers.Main) { finish() }
  }

  private suspend fun decryptCredential(file: File, password: String): Credentials? {
    runCatching { file.readBytes().inputStream() }
      .onFailure { e ->
        logcat(ERROR) { e.asLog("File to decrypt not found") }
        return null
      }
      .onSuccess { encryptedInput ->
        runCatching {
            withContext(Dispatchers.IO) {
              val outputStream = ByteArrayOutputStream()
              repository.decrypt(
                password,
                encryptedInput,
                outputStream,
              )
              outputStream
            }
          }
          .onFailure { e ->
            logcat(ERROR) { e.asLog("Decryption failed") }
            return null
          }
          .onSuccess { result ->
            return runCatching {
                val entry = passwordEntryFactory.create(result.toByteArray())
                AutofillPreferences.credentialsFromStoreEntry(this, file, entry, directoryStructure)
              }
              .getOrElse { e ->
                logcat(ERROR) { e.asLog("Failed to parse password entry") }
                return null
              }
          }
      }
    return null
  }
}
