/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
 * @author Andy Scherzinger
 * @author Tim Krüger
 * @author Marcel Hibbe
 * Copyright (C) 2022 Marcel Hibbe (dev@mhibbe.de)
 * Copyright (C) 2021-2022 Tim Krüger <t@timkrueger.me>
 * Copyright (C) 2021 Andy Scherzinger (info@andy-scherzinger.de)
 * Copyright (C) 2017-2018 Mario Danic <mario@lovelyhq.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.controllers

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import autodagger.AutoInjector
import com.afollestad.materialdialogs.LayoutMode.WRAP_CONTENT
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.datetime.dateTimePicker
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.HorizontalChangeHandler
import com.facebook.drawee.backends.pipeline.Fresco
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.talk.R
import com.nextcloud.talk.adapters.items.ParticipantItem
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.controllers.base.BaseController
import com.nextcloud.talk.controllers.bottomsheet.items.BasicListItemWithImage
import com.nextcloud.talk.controllers.bottomsheet.items.listItemsWithImage
import com.nextcloud.talk.controllers.util.viewBinding
import com.nextcloud.talk.conversation.info.GuestAccessHelper
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.ControllerConversationInfoBinding
import com.nextcloud.talk.events.EventStatus
import com.nextcloud.talk.jobs.DeleteConversationWorker
import com.nextcloud.talk.jobs.LeaveConversationWorker
import com.nextcloud.talk.models.json.conversations.Conversation
import com.nextcloud.talk.models.json.conversations.RoomOverall
import com.nextcloud.talk.models.json.converters.EnumNotificationLevelConverter
import com.nextcloud.talk.models.json.generic.GenericOverall
import com.nextcloud.talk.models.json.participants.Participant
import com.nextcloud.talk.models.json.participants.Participant.ActorType.CIRCLES
import com.nextcloud.talk.models.json.participants.Participant.ActorType.GROUPS
import com.nextcloud.talk.models.json.participants.Participant.ActorType.USERS
import com.nextcloud.talk.models.json.participants.ParticipantsOverall
import com.nextcloud.talk.repositories.conversations.ConversationsRepository
import com.nextcloud.talk.shareditems.activities.SharedItemsActivity
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.DateConstants
import com.nextcloud.talk.utils.DateUtils
import com.nextcloud.talk.utils.DisplayUtils
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.database.user.CapabilitiesUtilNew
import com.nextcloud.talk.utils.preferences.preferencestorage.DatabaseStorageModule
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.common.SmoothScrollLinearLayoutManager
import io.reactivex.Observer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class ConversationInfoController(args: Bundle) :
    BaseController(
        R.layout.controller_conversation_info,
        args
    ),
    FlexibleAdapter.OnItemClickListener {

    private val binding: ControllerConversationInfoBinding by viewBinding(ControllerConversationInfoBinding::bind)

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var conversationsRepository: ConversationsRepository

    @Inject
    lateinit var eventBus: EventBus

    private val conversationToken: String?
    private val conversationUser: User?
    private val hasAvatarSpacing: Boolean
    private val credentials: String?
    private var roomDisposable: Disposable? = null
    private var participantsDisposable: Disposable? = null

    private var databaseStorageModule: DatabaseStorageModule? = null
    private var conversation: Conversation? = null

    private var adapter: FlexibleAdapter<ParticipantItem>? = null
    private var userItems: MutableList<ParticipantItem> = ArrayList()

    private val workerData: Data?
        get() {
            if (!TextUtils.isEmpty(conversationToken) && conversationUser != null) {
                val data = Data.Builder()
                data.putString(BundleKeys.KEY_ROOM_TOKEN, conversationToken)
                data.putLong(BundleKeys.KEY_INTERNAL_USER_ID, conversationUser.id!!)
                return data.build()
            }

            return null
        }

    init {
        setHasOptionsMenu(true)
        NextcloudTalkApplication.sharedApplication?.componentApplication?.inject(this)
        conversationUser = args.getParcelable(BundleKeys.KEY_USER_ENTITY)
        conversationToken = args.getString(BundleKeys.KEY_ROOM_TOKEN)
        hasAvatarSpacing = args.getBoolean(BundleKeys.KEY_ROOM_ONE_TO_ONE, false)
        credentials = ApiUtils.getCredentials(conversationUser!!.username, conversationUser.token)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                router.popCurrentController()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        eventBus.register(this)

        if (databaseStorageModule == null) {
            databaseStorageModule = DatabaseStorageModule(conversationUser!!, conversationToken)
        }

        binding.notificationSettingsView.notificationSettings.setStorageModule(databaseStorageModule)
        binding.webinarInfoView.webinarSettings.setStorageModule(databaseStorageModule)
        binding.guestAccessView.guestAccessSettings.setStorageModule(databaseStorageModule)

        binding.deleteConversationAction.setOnClickListener { showDeleteConversationDialog() }
        binding.leaveConversationAction.setOnClickListener { leaveConversation() }
        binding.clearConversationHistory.setOnClickListener { showClearHistoryDialog() }
        binding.addParticipantsAction.setOnClickListener { addParticipants() }

        if (CapabilitiesUtilNew.hasSpreedFeatureCapability(conversationUser, "rich-object-list-media")) {
            binding.showSharedItemsAction.setOnClickListener { showSharedItems() }
        } else {
            binding.categorySharedItems.visibility = GONE
        }

        fetchRoomInfo()

        themeCategories()
        themeSwitchPreferences()
    }

    private fun themeSwitchPreferences() {
        binding.run {
            listOf(
                binding.webinarInfoView.conversationInfoLobby,
                binding.notificationSettingsView.callNotifications,
                binding.notificationSettingsView.conversationInfoPriorityConversation,
                binding.guestAccessView.guestAccessAllowSwitch,
                binding.guestAccessView.guestAccessPasswordSwitch
            ).forEach(viewThemeUtils.talk::colorSwitchPreference)
        }
    }

    private fun themeCategories() {
        binding.run {
            listOf(
                conversationInfoName,
                conversationDescription,
                otherRoomOptions,
                participantsListCategory,
                ownOptions,
                categorySharedItems,
                categoryConversationSettings,
                binding.guestAccessView.guestAccessCategory,
                binding.webinarInfoView.conversationInfoWebinar,
                binding.notificationSettingsView.notificationSettingsCategory
            ).forEach(viewThemeUtils.talk::colorPreferenceCategory)
        }
    }

    private fun showSharedItems() {
        val intent = Intent(activity, SharedItemsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra(BundleKeys.KEY_CONVERSATION_NAME, conversation?.displayName)
        intent.putExtra(BundleKeys.KEY_ROOM_TOKEN, conversationToken)
        intent.putExtra(BundleKeys.KEY_USER_ENTITY, conversationUser as Parcelable)
        intent.putExtra(SharedItemsActivity.KEY_USER_IS_OWNER_OR_MODERATOR, conversation?.isParticipantOwnerOrModerator)
        activity!!.startActivity(intent)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)

        binding.addParticipantsAction.visibility = GONE

        viewThemeUtils.platform.colorCircularProgressBar(binding.progressBar)
    }

    private fun setupWebinaryView() {
        if (CapabilitiesUtilNew.hasSpreedFeatureCapability(conversationUser, "webinary-lobby") &&
            webinaryRoomType(conversation!!) &&
            conversation!!.canModerate(conversationUser!!)
        ) {
            binding.webinarInfoView.webinarSettings.visibility = VISIBLE

            val isLobbyOpenToModeratorsOnly =
                conversation!!.lobbyState == Conversation.LobbyState.LOBBY_STATE_MODERATORS_ONLY
            (binding.webinarInfoView.conversationInfoLobby.findViewById<View>(R.id.mp_checkable) as SwitchCompat)
                .isChecked = isLobbyOpenToModeratorsOnly

            reconfigureLobbyTimerView()

            binding.webinarInfoView.startTimePreferences.setOnClickListener {
                MaterialDialog(activity!!, BottomSheet(WRAP_CONTENT)).show {
                    val currentTimeCalendar = Calendar.getInstance()
                    if (conversation!!.lobbyTimer != null && conversation!!.lobbyTimer != 0L) {
                        currentTimeCalendar.timeInMillis = conversation!!.lobbyTimer!! * DateConstants.SECOND_DIVIDER
                    }

                    dateTimePicker(
                        minDateTime = Calendar.getInstance(),
                        requireFutureDateTime = true,
                        currentDateTime = currentTimeCalendar,
                        show24HoursView = true,
                        dateTimeCallback = { _,
                            dateTime ->
                            reconfigureLobbyTimerView(dateTime)
                            submitLobbyChanges()
                        }
                    )
                }
            }

            (binding.webinarInfoView.conversationInfoLobby.findViewById<View>(R.id.mp_checkable) as SwitchCompat)
                .setOnCheckedChangeListener { _, _ ->
                    reconfigureLobbyTimerView()
                    submitLobbyChanges()
                }
        } else {
            binding.webinarInfoView.webinarSettings.visibility = GONE
        }
    }

    private fun webinaryRoomType(conversation: Conversation): Boolean {
        return conversation.type == Conversation.ConversationType.ROOM_GROUP_CALL ||
            conversation.type == Conversation.ConversationType.ROOM_PUBLIC_CALL
    }

    private fun reconfigureLobbyTimerView(dateTime: Calendar? = null) {
        val isChecked =
            (binding.webinarInfoView.conversationInfoLobby.findViewById<View>(R.id.mp_checkable) as SwitchCompat)
                .isChecked

        if (dateTime != null && isChecked) {
            conversation!!.lobbyTimer = (
                dateTime.timeInMillis - (dateTime.time.seconds * DateConstants.SECOND_DIVIDER)
                ) / DateConstants.SECOND_DIVIDER
        } else if (!isChecked) {
            conversation!!.lobbyTimer = 0
        }

        conversation!!.lobbyState = if (isChecked) Conversation.LobbyState
            .LOBBY_STATE_MODERATORS_ONLY else Conversation.LobbyState.LOBBY_STATE_ALL_PARTICIPANTS

        if (
            conversation!!.lobbyTimer != null &&
            conversation!!.lobbyTimer != java.lang.Long.MIN_VALUE &&
            conversation!!.lobbyTimer != 0L
        ) {
            binding.webinarInfoView.startTimePreferences.setSummary(
                DateUtils.getLocalDateStringFromTimestampForLobby(
                    conversation!!.lobbyTimer!!
                )
            )
        } else {
            binding.webinarInfoView.startTimePreferences.setSummary(R.string.nc_manual)
        }

        if (isChecked) {
            binding.webinarInfoView.startTimePreferences.visibility = VISIBLE
        } else {
            binding.webinarInfoView.startTimePreferences.visibility = GONE
        }
    }

    fun submitLobbyChanges() {
        val state = if (
            (binding.webinarInfoView.conversationInfoLobby.findViewById<View>(R.id.mp_checkable) as SwitchCompat)
                .isChecked
        ) 1 else 0

        val apiVersion = ApiUtils.getConversationApiVersion(conversationUser, intArrayOf(ApiUtils.APIv4, 1))

        ncApi.setLobbyForConversation(
            ApiUtils.getCredentials(conversationUser!!.username, conversationUser.token),
            ApiUtils.getUrlForRoomWebinaryLobby(apiVersion, conversationUser.baseUrl, conversation!!.token),
            state,
            conversation!!.lobbyTimer
        )
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe(object : Observer<GenericOverall> {
                override fun onComplete() {
                    // unused atm
                }

                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(t: GenericOverall) {
                    // unused atm
                }

                override fun onError(e: Throwable) {
                    // unused atm
                }
            })
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(eventStatus: EventStatus) {
        getListOfParticipants()
    }

    override fun onDetach(view: View) {
        super.onDetach(view)
        eventBus.unregister(this)
    }

    private fun showDeleteConversationDialog() {
        if (activity != null) {
            val dialogBuilder = MaterialAlertDialogBuilder(binding.conversationInfoName.context)
                .setIcon(viewThemeUtils.dialog.colorMaterialAlertDialogIcon(context, R.drawable.ic_delete_black_24dp))
                .setTitle(R.string.nc_delete_call)
                .setMessage(R.string.nc_delete_conversation_more)
                .setPositiveButton(R.string.nc_delete) { _, _ ->
                    deleteConversation()
                }
                .setNegativeButton(R.string.nc_cancel) { _, _ ->
                    // unused atm
                }
            viewThemeUtils.dialog
                .colorMaterialAlertDialogBackground(binding.conversationInfoName.context, dialogBuilder)
            val dialog = dialogBuilder.show()
            viewThemeUtils.platform.colorTextButtons(
                dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            )
        }
    }

    private fun setupAdapter() {
        if (activity != null) {
            if (adapter == null) {
                adapter = FlexibleAdapter(userItems, activity, true)
            }

            val layoutManager = SmoothScrollLinearLayoutManager(activity)
            binding.recyclerView.layoutManager = layoutManager
            binding.recyclerView.setHasFixedSize(true)
            binding.recyclerView.adapter = adapter

            adapter!!.addListener(this)
        }
    }

    private fun handleParticipants(participants: List<Participant>) {
        var userItem: ParticipantItem
        var participant: Participant

        userItems = ArrayList()
        var ownUserItem: ParticipantItem? = null

        for (i in participants.indices) {
            participant = participants[i]
            userItem = ParticipantItem(router.activity, participant, conversationUser, viewThemeUtils)
            if (participant.sessionId != null) {
                userItem.isOnline = !participant.sessionId.equals("0")
            } else {
                userItem.isOnline = !participant.sessionIds.isEmpty()
            }

            if (participant.calculatedActorType == USERS &&
                participant.calculatedActorId == conversationUser!!.userId
            ) {
                ownUserItem = userItem
                ownUserItem.model.sessionId = "-1"
                ownUserItem.isOnline = true
            } else {
                userItems.add(userItem)
            }
        }

        Collections.sort(userItems, ParticipantItemComparator())

        if (ownUserItem != null) {
            userItems.add(0, ownUserItem)
        }

        setupAdapter()

        binding.participantsListCategory.visibility = VISIBLE
        adapter!!.updateDataSet(userItems)
    }

    override val title: String
        get() =
            if (hasAvatarSpacing) {
                " " + resources!!.getString(R.string.nc_conversation_menu_conversation_info)
            } else {
                resources!!.getString(R.string.nc_conversation_menu_conversation_info)
            }

    private fun getListOfParticipants() {
        var apiVersion = 1
        // FIXME Fix API checking with guests?
        if (conversationUser != null) {
            apiVersion = ApiUtils.getConversationApiVersion(conversationUser, intArrayOf(ApiUtils.APIv4, 1))
        }

        val fieldMap = HashMap<String, Boolean>()
        fieldMap["includeStatus"] = true

        ncApi.getPeersForCall(
            credentials,
            ApiUtils.getUrlForParticipants(
                apiVersion,
                conversationUser!!.baseUrl,
                conversationToken
            ),
            fieldMap
        )
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe(object : Observer<ParticipantsOverall> {
                override fun onSubscribe(d: Disposable) {
                    participantsDisposable = d
                }

                @Suppress("Detekt.TooGenericExceptionCaught")
                override fun onNext(participantsOverall: ParticipantsOverall) {
                    try {
                        handleParticipants(participantsOverall.ocs!!.data!!)
                    } catch (npe: NullPointerException) {
                        // view binding can be null
                        // since this is called asynchronously and UI might have been destroyed in the meantime
                        Log.i(TAG, "UI destroyed - view binding already gone")
                    }
                }

                override fun onError(e: Throwable) {
                    // unused atm
                }

                override fun onComplete() {
                    participantsDisposable!!.dispose()
                }
            })
    }

    internal fun addParticipants() {
        val bundle = Bundle()
        val existingParticipantsId = arrayListOf<String>()

        for (userItem in userItems) {
            if (userItem.model.calculatedActorType == USERS) {
                existingParticipantsId.add(userItem.model.calculatedActorId!!)
            }
        }

        bundle.putBoolean(BundleKeys.KEY_ADD_PARTICIPANTS, true)
        bundle.putStringArrayList(BundleKeys.KEY_EXISTING_PARTICIPANTS, existingParticipantsId)
        bundle.putString(BundleKeys.KEY_TOKEN, conversation!!.token)

        router.pushController(
            (
                RouterTransaction.with(
                    ContactsController(bundle)
                )
                    .pushChangeHandler(
                        HorizontalChangeHandler()
                    )
                    .popChangeHandler(
                        HorizontalChangeHandler()
                    )
                )
        )
    }

    private fun leaveConversation() {
        workerData?.let {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequest.Builder(
                    LeaveConversationWorker::class
                        .java
                ).setInputData(it).build()
            )
            popTwoLastControllers()
        }
    }

    private fun showClearHistoryDialog() {
        if (activity != null) {
            val dialogBuilder = MaterialAlertDialogBuilder(binding.conversationInfoName.context)
                .setIcon(viewThemeUtils.dialog.colorMaterialAlertDialogIcon(context, R.drawable.ic_delete_black_24dp))
                .setTitle(R.string.nc_clear_history)
                .setMessage(R.string.nc_clear_history_warning)
                .setPositiveButton(R.string.nc_delete_all) { _, _ ->
                    clearHistory()
                }
                .setNegativeButton(R.string.nc_cancel) { _, _ ->
                    // unused atm
                }
            viewThemeUtils.dialog
                .colorMaterialAlertDialogBackground(binding.conversationInfoName.context, dialogBuilder)
            val dialog = dialogBuilder.show()
            viewThemeUtils.platform.colorTextButtons(
                dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            )
        }
    }

    private fun clearHistory() {
        val apiVersion = ApiUtils.getChatApiVersion(conversationUser, intArrayOf(1))

        ncApi.clearChatHistory(
            credentials,
            ApiUtils.getUrlForChat(apiVersion, conversationUser!!.baseUrl, conversationToken)
        )
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe(object : Observer<GenericOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(genericOverall: GenericOverall) {
                    Toast.makeText(context, context.getString(R.string.nc_clear_history_success), Toast.LENGTH_LONG)
                        .show()
                }

                override fun onError(e: Throwable) {
                    Toast.makeText(context, R.string.nc_common_error_sorry, Toast.LENGTH_LONG).show()
                    Log.e(TAG, "failed to clear chat history", e)
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    private fun deleteConversation() {
        workerData?.let {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequest.Builder(
                    DeleteConversationWorker::class.java
                ).setInputData(it).build()
            )
            popTwoLastControllers()
        }
    }

    private fun popTwoLastControllers() {
        var backstack = router.backstack
        backstack = backstack.subList(0, backstack.size - 2)
        router.setBackstack(backstack, HorizontalChangeHandler())
    }

    private fun fetchRoomInfo() {
        var apiVersion = 1
        // FIXME Fix API checking with guests?
        if (conversationUser != null) {
            apiVersion = ApiUtils.getConversationApiVersion(conversationUser, intArrayOf(ApiUtils.APIv4, 1))
        }

        ncApi.getRoom(credentials, ApiUtils.getUrlForRoom(apiVersion, conversationUser!!.baseUrl, conversationToken))
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe(object : Observer<RoomOverall> {
                override fun onSubscribe(d: Disposable) {
                    roomDisposable = d
                }

                @Suppress("Detekt.TooGenericExceptionCaught")
                override fun onNext(roomOverall: RoomOverall) {
                    try {
                        conversation = roomOverall.ocs!!.data

                        val conversationCopy = conversation

                        if (conversationCopy!!.canModerate(conversationUser)) {
                            binding.addParticipantsAction.visibility = VISIBLE
                            if (CapabilitiesUtilNew.hasSpreedFeatureCapability(conversationUser, "clear-history")) {
                                binding.clearConversationHistory.visibility = VISIBLE
                            } else {
                                binding.clearConversationHistory.visibility = GONE
                            }
                        } else {
                            binding.addParticipantsAction.visibility = GONE
                            binding.clearConversationHistory.visibility = GONE
                        }

                        if (isAttached && (!isBeingDestroyed || !isDestroyed)) {
                            binding.ownOptions.visibility = VISIBLE

                            setupWebinaryView()

                            if (!conversation!!.canLeave()) {
                                binding.leaveConversationAction.visibility = GONE
                            } else {
                                binding.leaveConversationAction.visibility = VISIBLE
                            }

                            if (!conversation!!.canDelete(conversationUser)) {
                                binding.deleteConversationAction.visibility = GONE
                            } else {
                                binding.deleteConversationAction.visibility = VISIBLE
                            }

                            if (Conversation.ConversationType.ROOM_SYSTEM == conversation!!.type) {
                                binding.notificationSettingsView.callNotifications.visibility = GONE
                            }

                            if (conversation!!.notificationCalls === null) {
                                binding.notificationSettingsView.callNotifications.visibility = GONE
                            } else {
                                binding.notificationSettingsView.callNotifications.value =
                                    conversationCopy.notificationCalls == 1
                            }

                            getListOfParticipants()

                            binding.progressBar.visibility = GONE

                            binding.conversationInfoName.visibility = VISIBLE

                            binding.displayNameText.text = conversation!!.displayName

                            if (conversation!!.description != null && !conversation!!.description!!.isEmpty()) {
                                binding.descriptionText.text = conversation!!.description
                                binding.conversationDescription.visibility = VISIBLE
                            }

                            loadConversationAvatar()
                            adjustNotificationLevelUI()
                            initExpiringMessageOption()

                            GuestAccessHelper(
                                this@ConversationInfoController,
                                binding,
                                conversation!!,
                                conversationUser
                            ).setupGuestAccess()

                            binding.notificationSettingsView.notificationSettings.visibility = VISIBLE
                        }
                    } catch (npe: NullPointerException) {
                        // view binding can be null
                        // since this is called asynchronously and UI might have been destroyed in the meantime
                        Log.i(TAG, "UI destroyed - view binding already gone")
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "failed to fetch room info", e)
                }

                override fun onComplete() {
                    roomDisposable!!.dispose()
                }
            })
    }

    private fun initExpiringMessageOption() {
        if (conversation!!.isParticipantOwnerOrModerator &&
            CapabilitiesUtilNew.hasSpreedFeatureCapability(conversationUser, "message-expiration")
        ) {
            databaseStorageModule?.setMessageExpiration(conversation!!.messageExpiration)
            binding.conversationInfoExpireMessages.setStorageModule(databaseStorageModule)
            binding.conversationInfoExpireMessages.visibility = View.VISIBLE
            binding.conversationInfoExpireMessagesExplanation.visibility = View.VISIBLE
        } else {
            binding.categoryConversationSettings.visibility = View.GONE
        }
    }

    private fun adjustNotificationLevelUI() {
        if (conversation != null) {
            if (
                conversationUser != null &&
                CapabilitiesUtilNew.hasSpreedFeatureCapability(conversationUser, "notification-levels")
            ) {
                binding.notificationSettingsView.conversationInfoMessageNotifications.isEnabled = true
                binding.notificationSettingsView.conversationInfoMessageNotifications.alpha = 1.0f

                if (conversation!!.notificationLevel != Conversation.NotificationLevel.DEFAULT) {
                    val stringValue: String =
                        when (EnumNotificationLevelConverter().convertToInt(conversation!!.notificationLevel)) {
                            NOTIFICATION_LEVEL_ALWAYS -> "always"
                            NOTIFICATION_LEVEL_MENTION -> "mention"
                            NOTIFICATION_LEVEL_NEVER -> "never"
                            else -> "mention"
                        }

                    binding.notificationSettingsView.conversationInfoMessageNotifications.value = stringValue
                } else {
                    setProperNotificationValue(conversation)
                }
            } else {
                binding.notificationSettingsView.conversationInfoMessageNotifications.isEnabled = false
                binding.notificationSettingsView.conversationInfoMessageNotifications.alpha = LOW_EMPHASIS_OPACITY
                setProperNotificationValue(conversation)
            }
        }
    }

    private fun setProperNotificationValue(conversation: Conversation?) {
        if (conversation!!.type == Conversation.ConversationType.ROOM_TYPE_ONE_TO_ONE_CALL) {
            // hack to see if we get mentioned always or just on mention
            if (CapabilitiesUtilNew.hasSpreedFeatureCapability(conversationUser, "mention-flag")) {
                binding.notificationSettingsView.conversationInfoMessageNotifications.value = "always"
            } else {
                binding.notificationSettingsView.conversationInfoMessageNotifications.value = "mention"
            }
        } else {
            binding.notificationSettingsView.conversationInfoMessageNotifications.value = "mention"
        }
    }

    private fun loadConversationAvatar() {
        when (conversation!!.type) {
            Conversation.ConversationType.ROOM_TYPE_ONE_TO_ONE_CALL -> if (!TextUtils.isEmpty(conversation!!.name)) {
                val draweeController = Fresco.newDraweeControllerBuilder()
                    .setOldController(binding.avatarImage.controller)
                    .setAutoPlayAnimations(true)
                    .setImageRequest(
                        DisplayUtils.getImageRequestForUrl(
                            ApiUtils.getUrlForAvatar(
                                conversationUser!!.baseUrl,
                                conversation!!.name,
                                true
                            ),
                            conversationUser
                        )
                    )
                    .build()
                binding.avatarImage.controller = draweeController
            }
            Conversation.ConversationType.ROOM_GROUP_CALL -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    binding.avatarImage.hierarchy.setPlaceholderImage(
                        DisplayUtils.getRoundedDrawable(
                            viewThemeUtils.talk.themePlaceholderAvatar(binding.avatarImage, R.drawable.ic_avatar_group)
                        )
                    )
                } else {
                    binding.avatarImage.hierarchy.setPlaceholderImage(
                        R.drawable.ic_circular_group
                    )
                }
            }
            Conversation.ConversationType.ROOM_PUBLIC_CALL -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    binding.avatarImage.hierarchy.setPlaceholderImage(
                        DisplayUtils.getRoundedDrawable(
                            viewThemeUtils.talk.themePlaceholderAvatar(binding.avatarImage, R.drawable.ic_avatar_link)
                        )
                    )
                } else {
                    binding.avatarImage.hierarchy.setPlaceholderImage(
                        R.drawable.ic_circular_link
                    )
                }
            }
            Conversation.ConversationType.ROOM_SYSTEM -> {
                val layers = arrayOfNulls<Drawable>(2)
                layers[0] = ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)
                layers[1] = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
                val layerDrawable = LayerDrawable(layers)
                binding.avatarImage.hierarchy.setPlaceholderImage(DisplayUtils.getRoundedDrawable(layerDrawable))
            }

            else -> {
                // unused atm
            }
        }
    }

    private fun toggleModeratorStatus(apiVersion: Int, participant: Participant) {
        val subscriber = object : Observer<GenericOverall> {
            override fun onSubscribe(d: Disposable) {
                // unused atm
            }

            override fun onNext(genericOverall: GenericOverall) {
                getListOfParticipants()
            }

            @SuppressLint("LongLogTag")
            override fun onError(e: Throwable) {
                Log.e(TAG, "Error toggling moderator status", e)
            }

            override fun onComplete() {
                // unused atm
            }
        }

        if (participant.type == Participant.ParticipantType.MODERATOR ||
            participant.type == Participant.ParticipantType.GUEST_MODERATOR
        ) {
            ncApi.demoteAttendeeFromModerator(
                credentials,
                ApiUtils.getUrlForRoomModerators(
                    apiVersion,
                    conversationUser!!.baseUrl,
                    conversation!!.token
                ),
                participant.attendeeId
            )
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe(subscriber)
        } else if (participant.type == Participant.ParticipantType.USER ||
            participant.type == Participant.ParticipantType.GUEST
        ) {
            ncApi.promoteAttendeeToModerator(
                credentials,
                ApiUtils.getUrlForRoomModerators(
                    apiVersion,
                    conversationUser!!.baseUrl,
                    conversation!!.token
                ),
                participant.attendeeId
            )
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe(subscriber)
        }
    }

    private fun toggleModeratorStatusLegacy(apiVersion: Int, participant: Participant) {
        val subscriber = object : Observer<GenericOverall> {
            override fun onSubscribe(d: Disposable) {
                // unused atm
            }

            override fun onNext(genericOverall: GenericOverall) {
                getListOfParticipants()
            }

            @SuppressLint("LongLogTag")
            override fun onError(e: Throwable) {
                Log.e(TAG, "Error toggling moderator status", e)
            }

            override fun onComplete() {
                // unused atm
            }
        }

        if (participant.type == Participant.ParticipantType.MODERATOR) {
            ncApi.demoteModeratorToUser(
                credentials,
                ApiUtils.getUrlForRoomModerators(
                    apiVersion,
                    conversationUser!!.baseUrl,
                    conversation!!.token
                ),
                participant.userId
            )
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe(subscriber)
        } else if (participant.type == Participant.ParticipantType.USER) {
            ncApi.promoteUserToModerator(
                credentials,
                ApiUtils.getUrlForRoomModerators(
                    apiVersion,
                    conversationUser!!.baseUrl,
                    conversation!!.token
                ),
                participant.userId
            )
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe(subscriber)
        }
    }

    fun removeAttendeeFromConversation(apiVersion: Int, participant: Participant) {
        if (apiVersion >= ApiUtils.APIv4) {
            ncApi.removeAttendeeFromConversation(
                credentials,
                ApiUtils.getUrlForAttendees(
                    apiVersion,
                    conversationUser!!.baseUrl,
                    conversation!!.token
                ),
                participant.attendeeId
            )
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe(object : Observer<GenericOverall> {
                    override fun onSubscribe(d: Disposable) {
                        // unused atm
                    }

                    override fun onNext(genericOverall: GenericOverall) {
                        getListOfParticipants()
                    }

                    @SuppressLint("LongLogTag")
                    override fun onError(e: Throwable) {
                        Log.e(TAG, "Error removing attendee from conversation", e)
                    }

                    override fun onComplete() {
                        // unused atm
                    }
                })
        } else {
            if (participant.type == Participant.ParticipantType.GUEST ||
                participant.type == Participant.ParticipantType.USER_FOLLOWING_LINK
            ) {
                ncApi.removeParticipantFromConversation(
                    credentials,
                    ApiUtils.getUrlForRemovingParticipantFromConversation(
                        conversationUser!!.baseUrl,
                        conversation!!.token,
                        true
                    ),
                    participant.sessionId
                )
                    ?.subscribeOn(Schedulers.io())
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.subscribe(object : Observer<GenericOverall> {
                        override fun onSubscribe(d: Disposable) {
                            // unused atm
                        }

                        override fun onNext(genericOverall: GenericOverall) {
                            getListOfParticipants()
                        }

                        @SuppressLint("LongLogTag")
                        override fun onError(e: Throwable) {
                            Log.e(TAG, "Error removing guest from conversation", e)
                        }

                        override fun onComplete() {
                            // unused atm
                        }
                    })
            } else {
                ncApi.removeParticipantFromConversation(
                    credentials,
                    ApiUtils.getUrlForRemovingParticipantFromConversation(
                        conversationUser!!.baseUrl,
                        conversation!!.token,
                        false
                    ),
                    participant.userId
                )
                    ?.subscribeOn(Schedulers.io())
                    ?.observeOn(AndroidSchedulers.mainThread())
                    ?.subscribe(object : Observer<GenericOverall> {
                        override fun onSubscribe(d: Disposable) {
                            // unused atm
                        }

                        override fun onNext(genericOverall: GenericOverall) {
                            getListOfParticipants()
                        }

                        @SuppressLint("LongLogTag")
                        override fun onError(e: Throwable) {
                            Log.e(TAG, "Error removing user from conversation", e)
                        }

                        override fun onComplete() {
                            // unused atm
                        }
                    })
            }
        }
    }

    override fun onItemClick(view: View?, position: Int): Boolean {
        if (!conversation!!.canModerate(conversationUser!!)) {
            return true
        }

        val userItem = adapter?.getItem(position) as ParticipantItem
        val participant = userItem.model

        val apiVersion = ApiUtils.getConversationApiVersion(conversationUser, intArrayOf(ApiUtils.APIv4, 1))

        if (participant.calculatedActorType == USERS && participant.calculatedActorId == conversationUser.userId) {
            if (participant.attendeePin?.isNotEmpty() == true) {
                val items = mutableListOf(
                    BasicListItemWithImage(
                        R.drawable.ic_lock_grey600_24px,
                        context.getString(R.string.nc_attendee_pin, participant.attendeePin)
                    )
                )
                MaterialDialog(activity!!, BottomSheet(WRAP_CONTENT)).show {
                    cornerRadius(res = R.dimen.corner_radius)

                    title(text = participant.displayName)
                    listItemsWithImage(items = items) { _, index, _ ->
                        if (index == 0) {
                            removeAttendeeFromConversation(apiVersion, participant)
                        }
                    }
                }
            }
            return true
        }

        if (participant.type == Participant.ParticipantType.OWNER) {
            // Can not moderate owner
            return true
        }

        if (participant.calculatedActorType == GROUPS) {
            val items = mutableListOf(
                BasicListItemWithImage(
                    R.drawable.ic_delete_grey600_24dp,
                    context.getString(R.string.nc_remove_group_and_members)
                )
            )
            MaterialDialog(activity!!, BottomSheet(WRAP_CONTENT)).show {
                cornerRadius(res = R.dimen.corner_radius)

                title(text = participant.displayName)
                listItemsWithImage(items = items) { _, index, _ ->
                    if (index == 0) {
                        removeAttendeeFromConversation(apiVersion, participant)
                    }
                }
            }
            return true
        }

        if (participant.calculatedActorType == CIRCLES) {
            val items = mutableListOf(
                BasicListItemWithImage(
                    R.drawable.ic_delete_grey600_24dp,
                    context.getString(R.string.nc_remove_circle_and_members)
                )
            )
            MaterialDialog(activity!!, BottomSheet(WRAP_CONTENT)).show {
                cornerRadius(res = R.dimen.corner_radius)

                title(text = participant.displayName)
                listItemsWithImage(items = items) { _, index, _ ->
                    if (index == 0) {
                        removeAttendeeFromConversation(apiVersion, participant)
                    }
                }
            }
            return true
        }

        val items = mutableListOf(
            BasicListItemWithImage(
                R.drawable.ic_lock_grey600_24px,
                context.getString(R.string.nc_attendee_pin, participant.attendeePin)
            ),
            BasicListItemWithImage(
                R.drawable.ic_pencil_grey600_24dp,
                context.getString(R.string.nc_promote)
            ),
            BasicListItemWithImage(
                R.drawable.ic_pencil_grey600_24dp,
                context.getString(R.string.nc_demote)
            ),
            BasicListItemWithImage(
                R.drawable.ic_delete_grey600_24dp,
                context.getString(R.string.nc_remove_participant)
            )
        )

        if (participant.type == Participant.ParticipantType.MODERATOR ||
            participant.type == Participant.ParticipantType.GUEST_MODERATOR
        ) {
            items.removeAt(1)
        } else if (participant.type == Participant.ParticipantType.USER ||
            participant.type == Participant.ParticipantType.GUEST
        ) {
            items.removeAt(2)
        } else {
            // Self joined users can not be promoted nor demoted
            items.removeAt(2)
            items.removeAt(1)
        }

        if (participant.attendeePin == null || participant.attendeePin!!.isEmpty()) {
            items.removeAt(0)
        }

        if (items.isNotEmpty()) {
            MaterialDialog(activity!!, BottomSheet(WRAP_CONTENT)).show {
                cornerRadius(res = R.dimen.corner_radius)

                title(text = participant.displayName)
                listItemsWithImage(items = items) { _, index, _ ->
                    var actionToTrigger = index
                    if (participant.attendeePin == null || participant.attendeePin!!.isEmpty()) {
                        actionToTrigger++
                    }
                    if (participant.type == Participant.ParticipantType.USER_FOLLOWING_LINK) {
                        actionToTrigger++
                    }

                    if (actionToTrigger == 0) {
                        // Pin, nothing to do
                    } else if (actionToTrigger == 1) {
                        // Promote/demote
                        if (apiVersion >= ApiUtils.APIv4) {
                            toggleModeratorStatus(apiVersion, participant)
                        } else {
                            toggleModeratorStatusLegacy(apiVersion, participant)
                        }
                    } else if (actionToTrigger == 2) {
                        // Remove from conversation
                        removeAttendeeFromConversation(apiVersion, participant)
                    }
                }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "ConversationInfo"
        private const val NOTIFICATION_LEVEL_ALWAYS: Int = 1
        private const val NOTIFICATION_LEVEL_MENTION: Int = 2
        private const val NOTIFICATION_LEVEL_NEVER: Int = 3
        private const val ID_DELETE_CONVERSATION_DIALOG = 0
        private const val ID_CLEAR_CHAT_DIALOG = 1
        private val LOW_EMPHASIS_OPACITY: Float = 0.38f
    }

    /**
     * Comparator for participants, sorts by online-status, moderator-status and display name.
     */
    class ParticipantItemComparator : Comparator<ParticipantItem> {
        override fun compare(left: ParticipantItem, right: ParticipantItem): Int {
            val leftIsGroup = left.model.actorType == GROUPS || left.model.actorType == CIRCLES
            val rightIsGroup = right.model.actorType == GROUPS || right.model.actorType == CIRCLES
            if (leftIsGroup != rightIsGroup) {
                // Groups below participants
                return if (rightIsGroup) {
                    -1
                } else {
                    1
                }
            }

            if (left.isOnline && !right.isOnline) {
                return -1
            } else if (!left.isOnline && right.isOnline) {
                return 1
            }

            val moderatorTypes = ArrayList<Participant.ParticipantType>()
            moderatorTypes.add(Participant.ParticipantType.MODERATOR)
            moderatorTypes.add(Participant.ParticipantType.OWNER)
            moderatorTypes.add(Participant.ParticipantType.GUEST_MODERATOR)

            if (moderatorTypes.contains(left.model.type) && !moderatorTypes.contains(right.model.type)) {
                return -1
            } else if (!moderatorTypes.contains(left.model.type) && moderatorTypes.contains(right.model.type)) {
                return 1
            }

            return left.model.displayName!!.lowercase(Locale.ROOT).compareTo(
                right.model.displayName!!.lowercase(Locale.ROOT)
            )
        }
    }
}
