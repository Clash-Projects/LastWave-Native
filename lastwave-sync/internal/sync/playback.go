package sync

import (
	"github.com/Clash-Projects/lastwave-sync/internal/protocol"
)

// MaxTransitCompensationMs is the upper bound on network delay compensation (3 seconds).
const MaxTransitCompensationMs = 3000

// CompensateTransit adjusts the playback position to account for network transit time
// between client event capture and server reception.
func CompensateTransit(posMs int64, clientTimeMs int64, serverTimeMs int64) int64 {
	if clientTimeMs <= 0 || clientTimeMs >= serverTimeMs {
		return posMs
	}

	transitDelay := serverTimeMs - clientTimeMs
	if transitDelay > MaxTransitCompensationMs {
		transitDelay = MaxTransitCompensationMs
	}

	return posMs + transitDelay
}

// CalculateCurrentPosition calculates the real-time position of the current track
// considering whether it is actively playing and how much time has elapsed since the
// last server state update. Clamps to track duration and 0.
func CalculateCurrentPosition(state *protocol.PlaybackState, nowMs int64) int64 {
	if state == nil {
		return 0
	}

	pos := state.PositionMs
	if state.IsPlaying && nowMs > state.ServerTimeMs {
		elapsed := nowMs - state.ServerTimeMs
		pos += elapsed
	}

	if pos < 0 {
		pos = 0
	}

	if state.CurrentTrack != nil && state.CurrentTrack.DurationMs > 0 {
		if pos > state.CurrentTrack.DurationMs {
			pos = state.CurrentTrack.DurationMs
		}
	}

	return pos
}
