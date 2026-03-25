#include <dbus/dbus.h>
#include <pthread.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>

static void (*g_callback)(int) = NULL;
static DBusConnection *g_conn = NULL;
static pthread_t g_thread;
static volatile int g_running = 0;

static const char *MPRIS_IFACE = "org.mpris.MediaPlayer2";
static const char *PLAYER_IFACE = "org.mpris.MediaPlayer2.Player";
static const char *OBJ_PATH = "/org/mpris/MediaPlayer2";

static char g_title[512]  = "";
static char g_artist[512] = "";
static int64_t g_duration_us = 0;
static int64_t g_position_us = 0;
static int g_is_playing = 0;

static void handle_method_call(DBusConnection *conn, DBusMessage *msg)
{
    const char *iface = dbus_message_get_interface(msg);
    const char *member = dbus_message_get_member(msg);
    if (!iface || !member) return;

    if (strcmp(iface, PLAYER_IFACE) == 0) {
        if      (strcmp(member, "Play") == 0 ||
                 strcmp(member, "Pause") == 0 ||
                 strcmp(member, "PlayPause") == 0) { if (g_callback) g_callback(0); }
        else if (strcmp(member, "Next") == 0)      { if (g_callback) g_callback(1); }
        else if (strcmp(member, "Previous") == 0)  { if (g_callback) g_callback(2); }
        else if (strcmp(member, "Seek") == 0) {
            int64_t offset = 0;
            DBusMessageIter iter;
            dbus_message_iter_init(msg, &iter);
            if (dbus_message_iter_get_arg_type(&iter) == DBUS_TYPE_INT64)
                dbus_message_iter_get_basic(&iter, &offset);
            if (g_callback) g_callback(offset > 0 ? 3 : 4);
        }
    }

    DBusMessage *reply = dbus_message_new_method_return(msg);
    if (reply) { dbus_connection_send(conn, reply, NULL); dbus_message_unref(reply); }
}

static void send_properties_reply(DBusConnection *conn, DBusMessage *msg)
{
    DBusMessage *reply = dbus_message_new_method_return(msg);
    if (!reply) return;
    DBusMessageIter iter, variant, dict, entry;
    dbus_message_iter_init_append(reply, &iter);
    dbus_message_iter_open_container(&iter, DBUS_TYPE_VARIANT, "a{sv}", &variant);
    dbus_message_iter_open_container(&variant, DBUS_TYPE_ARRAY, "{sv}", &dict);

    const char *status = g_is_playing ? "Playing" : "Paused";
    dbus_message_iter_open_container(&dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry);
    const char *key = "PlaybackStatus";
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    DBusMessageIter sv;
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "s", &sv);
    dbus_message_iter_append_basic(&sv, DBUS_TYPE_STRING, &status);
    dbus_message_iter_close_container(&entry, &sv);
    dbus_message_iter_close_container(&dict, &entry);

    dbus_message_iter_close_container(&variant, &dict);
    dbus_message_iter_close_container(&iter, &variant);
    dbus_connection_send(conn, reply, NULL);
    dbus_message_unref(reply);
}

static void *dbus_loop(void *arg)
{
    while (g_running) {
        dbus_connection_read_write(g_conn, 100);
        DBusMessage *msg;
        while ((msg = dbus_connection_pop_message(g_conn)) != NULL) {
            if (dbus_message_get_type(msg) == DBUS_MESSAGE_TYPE_METHOD_CALL)
                handle_method_call(g_conn, msg);
            dbus_message_unref(msg);
        }
    }
    return NULL;
}

int linux_mpris_start(const char *player_name, void (*callback)(int command))
{
    DBusError err;
    dbus_error_init(&err);
    g_conn = dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (dbus_error_is_set(&err)) { dbus_error_free(&err); return -1; }
    if (!g_conn) return -1;

    char bus_name[256];
    snprintf(bus_name, sizeof(bus_name), "org.mpris.MediaPlayer2.%s", player_name);
    int ret = dbus_bus_request_name(g_conn, bus_name,
            DBUS_NAME_FLAG_REPLACE_EXISTING, &err);
    if (dbus_error_is_set(&err) || ret != DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER) {
        dbus_error_free(&err); dbus_connection_unref(g_conn); g_conn = NULL; return -1;
    }

    g_callback = callback;
    g_running = 1;
    pthread_create(&g_thread, NULL, dbus_loop, NULL);
    return 0;
}

void linux_mpris_update(const char *title, const char *artist,
                        int64_t duration_us, int64_t position_us, int is_playing)
{
    if (!g_conn) return;
    strncpy(g_title,  title  ? title  : "", sizeof(g_title)  - 1);
    strncpy(g_artist, artist ? artist : "", sizeof(g_artist) - 1);
    g_duration_us = duration_us;
    g_position_us = position_us;
    g_is_playing  = is_playing;

    DBusMessage *signal = dbus_message_new_signal(OBJ_PATH,
            "org.freedesktop.DBus.Properties", "PropertiesChanged");
    if (signal) {
        DBusMessageIter iter;
        dbus_message_iter_init_append(signal, &iter);
        const char *iface = PLAYER_IFACE;
        dbus_message_iter_append_basic(&iter, DBUS_TYPE_STRING, &iface);
        DBusMessageIter arr;
        dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "{sv}", &arr);
        dbus_message_iter_close_container(&iter, &arr);
        dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "s", &arr);
        dbus_message_iter_close_container(&iter, &arr);
        dbus_connection_send(g_conn, signal, NULL);
        dbus_message_unref(signal);
    }
}

void linux_mpris_stop(void)
{
    if (!g_running) return;
    g_running = 0;
    pthread_join(g_thread, NULL);
    if (g_conn) { dbus_connection_unref(g_conn); g_conn = NULL; }
    g_callback = NULL;
}
