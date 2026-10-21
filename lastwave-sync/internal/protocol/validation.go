package protocol

import (
	"errors"
	"strings"
	"unicode"
	"unicode/utf8"
)

var (
	ErrNameEmpty        = errors.New("display name cannot be empty")
	ErrNameTooLong      = errors.New("display name cannot exceed 32 characters")
	ErrNameControlChars = errors.New("display name cannot contain control characters")
	ErrChatEmpty        = errors.New("chat message cannot be empty")
	ErrChatTooLong      = errors.New("chat message cannot exceed 500 characters")
)

// ValidateName validates and sanitizes a user display name.
// It trims whitespace, ensures length is between 1 and 32 characters,
// and rejects control characters like newlines or null bytes.
func ValidateName(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", ErrNameEmpty
	}

	charCount := utf8.RuneCountInString(trimmed)
	if charCount > 32 {
		return "", ErrNameTooLong
	}

	for _, r := range trimmed {
		if unicode.IsControl(r) {
			return "", ErrNameControlChars
		}
	}

	return trimmed, nil
}

// SanitizeChatText ensures chat messages are non-empty and under 500 runes.
func SanitizeChatText(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", ErrChatEmpty
	}

	charCount := utf8.RuneCountInString(trimmed)
	if charCount > 500 {
		return "", ErrChatTooLong
	}

	return trimmed, nil
}

// SanitizeTrack validates that a track has both an ID and a Title.
func SanitizeTrack(track *TrackInfo) bool {
	if track == nil {
		return false
	}
	track.ID = strings.TrimSpace(track.ID)
	track.Title = strings.TrimSpace(track.Title)
	track.Artist = strings.TrimSpace(track.Artist)
	return track.ID != "" && track.Title != ""
}
