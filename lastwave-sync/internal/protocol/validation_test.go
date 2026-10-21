package protocol

import (
	"strings"
	"testing"
)

func TestValidateName(t *testing.T) {
	tests := []struct {
		name      string
		input     string
		want      string
		expectErr bool
	}{
		{
			name:      "empty name",
			input:     "",
			want:      "",
			expectErr: true,
		},
		{
			name:      "whitespace only name",
			input:     "    \t\n  ",
			want:      "",
			expectErr: true,
		},
		{
			name:      "valid clean name",
			input:     "Ajisth",
			want:      "Ajisth",
			expectErr: false,
		},
		{
			name:      "name with padding whitespace is trimmed",
			input:     "   Sarah Connor   ",
			want:      "Sarah Connor",
			expectErr: false,
		},
		{
			name:      "name exceeds 32 characters",
			input:     strings.Repeat("a", 33),
			want:      "",
			expectErr: true,
		},
		{
			name:      "name with 32 characters is allowed",
			input:     strings.Repeat("a", 32),
			want:      strings.Repeat("a", 32),
			expectErr: false,
		},
		{
			name:      "name with internal control characters is rejected",
			input:     "Alice\nBob",
			want:      "",
			expectErr: true,
		},
		{
			name:      "name with null byte is rejected",
			input:     "Alice\x00Bob",
			want:      "",
			expectErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := ValidateName(tt.input)
			if (err != nil) != tt.expectErr {
				t.Fatalf("ValidateName(%q) error = %v, expectErr %v", tt.input, err, tt.expectErr)
			}
			if got != tt.want {
				t.Errorf("ValidateName(%q) = %q, want %q", tt.input, got, tt.want)
			}
		})
	}
}

func TestSanitizeChatText(t *testing.T) {
	tests := []struct {
		name      string
		input     string
		want      string
		expectErr bool
	}{
		{
			name:      "empty text",
			input:     "",
			want:      "",
			expectErr: true,
		},
		{
			name:      "whitespace only",
			input:     "   \n  \t ",
			want:      "",
			expectErr: true,
		},
		{
			name:      "valid chat message",
			input:     "Loving this track!",
			want:      "Loving this track!",
			expectErr: false,
		},
		{
			name:      "message exceeds 500 characters",
			input:     strings.Repeat("x", 501),
			want:      "",
			expectErr: true,
		},
		{
			name:      "message with 500 characters passes",
			input:     strings.Repeat("x", 500),
			want:      strings.Repeat("x", 500),
			expectErr: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := SanitizeChatText(tt.input)
			if (err != nil) != tt.expectErr {
				t.Fatalf("SanitizeChatText() error = %v, expectErr %v", err, tt.expectErr)
			}
			if got != tt.want {
				t.Errorf("SanitizeChatText() = %q, want %q", got, tt.want)
			}
		})
	}
}
