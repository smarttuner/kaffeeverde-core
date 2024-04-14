/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * === WARNING ===
 *
 * The original source file used for this file is available here:
 * https://android.googlesource.com/platform/frameworks/support/+/HEAD/lifecycle/lifecycle-viewmodel-savedstate/src/main/java/androidx/lifecycle/SavedStateHandleSupport.kt
 *
 * The content of this file is a port of the original work with some additions
 *
 */
package net.smarttuner.kaffeeverde.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.Companion.VIEW_MODEL_KEY
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider
import net.smarttuner.kaffeeverde.core.Bundle
import net.smarttuner.kaffeeverde.core.getBundle
import net.smarttuner.kaffeeverde.core.putBundle
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set


private const val VIEWMODEL_KEY = "androidx.lifecycle.internal.SavedStateHandlesVM"
private const val SAVED_STATE_KEY = "androidx.lifecycle.internal.SavedStateHandlesProvider"
/**
 * Enables the support of [SavedStateHandle] in a component.
 *
 * After this method, [createSavedStateHandle] can be called on [CreationExtras] containing this
 * [SavedStateRegistryOwner] / [ViewModelStoreOwner].
 *
 * Must be called while component is in `INITIALIZED` or `CREATED` state and before
 * a [ViewModel] with [SavedStateHandle] is requested.
 */

fun <T> T.enableSavedStateHandles()
        where T : SavedStateRegistryOwner, T : ViewModelStoreOwner {
    val currentState = lifecycle.currentState
    require(
        currentState == Lifecycle.State.INITIALIZED || currentState == Lifecycle.State.CREATED
    )
    // Add the SavedStateProvider used to save SavedStateHandles
    // if we haven't already registered the provider
    if (savedStateRegistry.getSavedStateProvider(SAVED_STATE_KEY) == null) {
        val provider = SavedStateHandlesProvider(savedStateRegistry, this)
        savedStateRegistry.registerSavedStateProvider(SAVED_STATE_KEY, provider)
        lifecycle.addObserver(SavedStateHandleAttacher(provider))
    }
}
private fun createSavedStateHandle(
    savedStateRegistryOwner: SavedStateRegistryOwner,
    viewModelStoreOwner: ViewModelStoreOwner,
    key: String,
    defaultArgs: Bundle?
): SavedStateHandle {
    val provider = savedStateRegistryOwner.savedStateHandlesProvider
    val viewModel = viewModelStoreOwner.savedStateHandlesVM
    // If we already have a reference to a previously created SavedStateHandle
    // for a given key stored in our ViewModel, use that. Otherwise, create
    // a new SavedStateHandle, providing it any restored state we might have saved
    return viewModel.handles[key] ?: SavedStateHandle.createHandle(
        provider.consumeRestoredStateForKey(key), defaultArgs
    ).also { viewModel.handles[key] = it }
}
/**
 * Creates `SavedStateHandle` that can be used in your ViewModels
 *
 * This function requires [enableSavedStateHandles] call during the component
 * initialization. Latest versions of androidx components like `ComponentActivity`, `Fragment`,
 * `NavBackStackEntry` makes this call automatically.
 *
 * This [CreationExtras] must contain [SAVED_STATE_REGISTRY_OWNER_KEY],
 * [VIEW_MODEL_STORE_OWNER_KEY] and [VIEW_MODEL_KEY].
 *
 * @throws IllegalArgumentException if this `CreationExtras` are missing required keys:
 * `ViewModelStoreOwnerKey`, `SavedStateRegistryOwnerKey`, `VIEW_MODEL_KEY`
 */

public fun CreationExtras.createSavedStateHandle(): SavedStateHandle {
    val savedStateRegistryOwner = this[SAVED_STATE_REGISTRY_OWNER_KEY]
        ?: throw IllegalArgumentException(
            "CreationExtras must have a value by `SAVED_STATE_REGISTRY_OWNER_KEY`"
        )
    val viewModelStateRegistryOwner = this[VIEW_MODEL_STORE_OWNER_KEY]
        ?: throw IllegalArgumentException(
            "CreationExtras must have a value by `VIEW_MODEL_STORE_OWNER_KEY`"
        )
    val defaultArgs = this[DEFAULT_ARGS_KEY]
    val key = this[VIEW_MODEL_KEY] ?: throw IllegalArgumentException(
        "CreationExtras must have a value by `VIEW_MODEL_KEY`"
    )
    return createSavedStateHandle(
        savedStateRegistryOwner, viewModelStateRegistryOwner, key, defaultArgs
    )
}
internal val ViewModelStoreOwner.savedStateHandlesVM: SavedStateHandlesVM
    get() {
        val savedStateHandlesVM = SavedStateHandlesVM()
        val vmp = ViewModelProvider.create(owner = this, factory = viewModelFactory {
            initializer { savedStateHandlesVM }
        })
        var vm = vmp[VIEWMODEL_KEY, SavedStateHandlesVM::class]
        return vm
    }
internal val SavedStateRegistryOwner.savedStateHandlesProvider: SavedStateHandlesProvider
    get() = savedStateRegistry.getSavedStateProvider(SAVED_STATE_KEY) as? SavedStateHandlesProvider
        ?: throw IllegalStateException("enableSavedStateHandles() wasn't called " +
                "prior to createSavedStateHandle() call")
internal class SavedStateHandlesVM : ViewModel() {
    val handles = mutableMapOf<String, SavedStateHandle>()
}
/**
 * This single SavedStateProvider is responsible for saving the state of every
 * SavedStateHandle associated with the SavedState/ViewModel pair.
 */
internal class SavedStateHandlesProvider(
    private val savedStateRegistry: SavedStateRegistry,
    viewModelStoreOwner: ViewModelStoreOwner
) : SavedStateRegistry.SavedStateProvider {
    private var restored = false
    private var restoredState: Bundle? = null
    private val viewModel by lazy {
        viewModelStoreOwner.savedStateHandlesVM
    }
    override fun saveState(): Bundle {
        val restoredState = restoredState
        return Bundle().apply {
            // Ensure that even if ViewModels aren't recreated after process death and recreation
            // that we keep their state until they are recreated
            if (restoredState != null) {
                putAll(restoredState)
            }
            // But if we do have ViewModels, prefer their state over what we may
            // have restored
            viewModel.handles.forEach { (key, handle) ->
                val savedState = handle.savedStateProvider().saveState()
                if (!savedState.isEmpty()) {
                    putBundle(key, savedState)
                }
            }
        }.also {
            // After we've saved the state, allow restoring a second time
            restored = false
        }
    }
    /**
     * Restore the state from the SavedStateRegistry if it hasn't already been restored.
     */
    fun performRestore() {
        if (!restored) {
            restoredState = savedStateRegistry.consumeRestoredStateForKey(SAVED_STATE_KEY)
            restored = true
            // Grab a reference to the ViewModel for later usage when we saveState()
            // This ensures that even if saveState() is called after the Lifecycle is
            // DESTROYED, we can still save the state
            viewModel
        }
    }
    /**
     * Restore the state associated with a particular SavedStateHandle, identified by its [key]
     */
    fun consumeRestoredStateForKey(key: String): Bundle? {
        performRestore()
        return restoredState?.getBundle(key).also {
            restoredState?.remove(key)
            if (restoredState?.isEmpty() == true) {
                restoredState = null
            }
        }
    }
}
// it reconnects existent SavedStateHandles to SavedStateRegistryOwner when it is recreated
internal class SavedStateHandleAttacher(
    private val provider: SavedStateHandlesProvider
) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        check(event == Lifecycle.Event.ON_CREATE) {
            "Next event must be ON_CREATE, it was $event"
        }
        source.lifecycle.removeObserver(this)
        // onRecreated() is called after the Lifecycle reaches CREATED, so we
        // eagerly restore the state as part of this call to ensure it consumed
        // even if no ViewModels are actually created during this cycle of the Lifecycle
        provider.performRestore()
    }
}
/**
 * A key for [SavedStateRegistryOwner] that corresponds to [ViewModelStoreOwner]
 * of a [ViewModel] that is being created.
 */
val SAVED_STATE_REGISTRY_OWNER_KEY = object : CreationExtras.Key<SavedStateRegistryOwner> {}
/**
 * A key for [ViewModelStoreOwner] that is an owner of a [ViewModel] that is being created.
 */
val VIEW_MODEL_STORE_OWNER_KEY = object : CreationExtras.Key<ViewModelStoreOwner> {}
/**
 * A key for default arguments that should be passed to [SavedStateHandle] if needed.
 */
val DEFAULT_ARGS_KEY = object : CreationExtras.Key<Bundle> {}