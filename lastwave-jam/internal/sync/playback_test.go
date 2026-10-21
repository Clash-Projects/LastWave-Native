package sync

import (
	"testing"

	"github.com/Clash-Projects/lastwave-jam/internal/protocol"
)

func TestCompensateTransit(t *testing.T) {
	serverNow := int64(1700000005000)

	tests := []struct {
		name         string
		posMs        int64
		clientTimeMs int64
		serverTimeMs int64
		want         int64
	}{
		{
			name:         "zero client time returns unchanged position",
			posMs:        10000,
			clientTimeMs: 0,
			serverTimeMs: serverNow,
			want:         10000,
		},
		{
			name:         "client time in future returns unchanged position",
			posMs:        10000,
			clientTimeMs: serverNow + 500,
			serverTimeMs: serverNow,
			want:         10000,
		},
		{
			name:         "250ms transit delay adds 250ms",
			posMs:        10000,
			clientTimeMs: serverNow - 250,
			serverTimeMs: serverNow,
			want:         10250,
		},
		{
			name:         "delay exceeding cap is clamped to max transit compensation",
			posMs:        10000,
			clientTimeMs: serverNow - 10000,
			serverTimeMs: serverNow,
			want:         10000 + MaxTransitCompensationMs,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := CompensateTransit(tt.posMs, tt.clientTimeMs, tt.serverTimeMs)
			if got != tt.want {
				t.Errorf("CompensateTransit() = %d, want %d", got, tt.want)
			}
		})
	}
}

func TestCalculateCurrentPosition(t *testing.T) {
	serverTime := int64(1700000000000)

	t.Run("paused playback returns static position", func(t *testing.T) {
		state := protocol.PlaybackState{
			PositionMs:   5000,
			ServerTimeMs: serverTime,
			IsPlaying:    false,
		}
		got := CalculateCurrentPosition(&state, serverTime+1500)
		if got != 5000 {
			t.Errorf("got %d, want 5000", got)
		}
	})

	t.Run("playing advances by elapsed time", func(t *testing.T) {
		state := protocol.PlaybackState{
			PositionMs:   5000,
			ServerTimeMs: serverTime,
			IsPlaying:    true,
		}
		got := CalculateCurrentPosition(&state, serverTime+2000)
		if got != 7000 {
			t.Errorf("got %d, want 7000", got)
		}
	})

	t.Run("playing clamps to track duration if exceeded", func(t *testing.T) {
		state := protocol.PlaybackState{
			CurrentTrack: &protocol.TrackInfo{
				ID:         "trk_1",
				Title:      "Test Song",
				DurationMs: 60000,
			},
			PositionMs:   55000,
			ServerTimeMs: serverTime,
			IsPlaying:    true,
		}
		got := CalculateCurrentPosition(&state, serverTime+10000)
		if got != 60000 {
			t.Errorf("got %d, want 60000 (duration clamp)", got)
		}
	})

	t.Run("negative position clamped to zero", func(t *testing.T) {
		state := protocol.PlaybackState{
			PositionMs:   -500,
			ServerTimeMs: serverTime,
			IsPlaying:    false,
		}
		got := CalculateCurrentPosition(&state, serverTime)
		if got != 0 {
			t.Errorf("got %d, want 0", got)
		}
	})
}
