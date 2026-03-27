// macOS Now Playing integration via MPRemoteCommandCenter / MPNowPlayingInfoCenter.
//
// Keyboard media keys (play/pause, next, previous) work reliably through
// the handlers registered below.
//
// Control Centre widget: play/pause button works, but the << / >> navigation
// buttons appear disabled ~90% of the time. This is a known limitation of
// CLI (non-bundle) processes — macOS's mediaremoted daemon does not reliably
// activate navigation buttons without a full NSApplication on the main thread.
// Attempted workarounds that did NOT solve the problem:
//   - NSLog(@"") on calling thread (Foundation runtime init)
//   - [NSApplication sharedApplication] + setActivationPolicy on calling thread
//   - [NSApplication sharedApplication] + [NSApp run] on RunLoop thread (crash)
//   - MRMediaRemoteSetCanBeNowPlayingApplication(true) via private MediaRemote.framework
//   - Periodic NSTimer re-asserting enabled=YES every 0.5s
//   - Placeholder nowPlayingInfo with playbackRate=1 before real metadata
//   - 1-second RunLoop drain before signalling caller
//   - PlaybackQueueCount/Index hints (9999/4999)
//   - skipForwardCommand/skipBackwardCommand (position-dependent, worse)
//   - Various combinations of the above
// The root cause is that NSApplication must run on the process main thread,
// which is occupied by the JVM. A helper-process architecture would solve
// this but adds significant complexity. For now, only play/pause is
// guaranteed in the Control Centre widget; keyboard media keys work fully.

#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>

static void (*g_callback)(int) = NULL;
static NSThread *g_thread = NULL;
static int g_guard = 0;
static dispatch_semaphore_t g_registered_sem = NULL;

@interface MediaSessionRunner : NSObject
- (void)runLoop:(id)arg;
@end

@implementation MediaSessionRunner
- (void)runLoop:(id)arg {
    @autoreleasepool {
        MPRemoteCommandCenter *cc = [MPRemoteCommandCenter sharedCommandCenter];

        [cc.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            if (g_callback) g_callback(0); return MPRemoteCommandHandlerStatusSuccess;
        }];
        [cc.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            if (g_callback) g_callback(0); return MPRemoteCommandHandlerStatusSuccess;
        }];
        [cc.togglePlayPauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            if (g_callback) g_callback(0); return MPRemoteCommandHandlerStatusSuccess;
        }];

        // Keyboard next/previous media keys are delivered through these handlers.
        // The corresponding Control Centre widget buttons may appear disabled
        // (see file header comment) but the keyboard keys still work.
        [cc.nextTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            if (g_callback) g_callback(3); return MPRemoteCommandHandlerStatusSuccess;
        }];
        [cc.previousTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            if (g_callback) g_callback(4); return MPRemoteCommandHandlerStatusSuccess;
        }];

        dispatch_semaphore_signal(g_registered_sem);

        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate distantFuture]];
    }
}
@end

void macos_register_commands(void (*callback)(int command))
{
    if (g_guard) return;
    g_guard = 1;
    g_callback = callback;

    g_registered_sem = dispatch_semaphore_create(0);

    MediaSessionRunner *runner = [[MediaSessionRunner alloc] init];
    g_thread = [[NSThread alloc] initWithTarget:runner
                                       selector:@selector(runLoop:)
                                         object:nil];
    [g_thread start];

    dispatch_semaphore_wait(g_registered_sem,
                            dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC));
}

void macos_update_now_playing(const char *title, const char *artist,
                               double duration_sec, double position_sec,
                               int is_playing)
{
    if (!g_guard) return;
    NSMutableDictionary *info = [NSMutableDictionary dictionary];
    if (title && strlen(title) > 0)
        info[MPMediaItemPropertyTitle] = @(title);
    if (artist && strlen(artist) > 0)
        info[MPMediaItemPropertyArtist] = @(artist);
    info[MPMediaItemPropertyPlaybackDuration] = @(duration_sec);
    info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(position_sec);
    info[MPNowPlayingInfoPropertyPlaybackRate] = @(is_playing ? 1.0 : 0.0);
    info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = @(1.0);
    [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = info;
}

void macos_unregister(void)
{
    if (!g_guard) return;
    g_guard = 0;
    g_callback = NULL;

    MPRemoteCommandCenter *cc = [MPRemoteCommandCenter sharedCommandCenter];
    [cc.playCommand removeTarget:nil];
    [cc.pauseCommand removeTarget:nil];
    [cc.togglePlayPauseCommand removeTarget:nil];
    [cc.nextTrackCommand removeTarget:nil];
    [cc.previousTrackCommand removeTarget:nil];

    // Setting playbackState to Stopped signals mediaremoted to remove the
    // now-playing session immediately. Without this, mediaremoted keeps the
    // icon visible for several seconds waiting for the app to resume.
    // Requires macOS 10.14+.
    MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
    center.playbackState = MPNowPlayingPlaybackStateStopped;
    center.nowPlayingInfo = nil;

    [g_thread cancel];
    g_thread = nil;
}
