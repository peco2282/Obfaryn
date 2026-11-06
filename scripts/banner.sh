#!/usr/bin/env bash
# Obfaryn installer banner: ASCII logo -> diagonal obfuscation wave.
# Source this file and call obfaryn_banner, or execute it directly.

OBFARYN_ART=(
'   ____  __    ____'
'  / __ \/ /_  / __/___ ________  ______'
' / / / / __ \/ /_/ __ `/ ___/ / / / __ \'
'/ /_/ / /_/ / __/ /_/ / /  / /_/ / / / /'
'\____/_.___/_/  \__,_/_/   \__, /_/ /_/'
'                           /____/'
)

obfaryn_banner() {
    local reset=$'\033[0m'
    local purple=$'\033[38;2;139;92;246m'
    local cyan=$'\033[1;38;2;51;245;255m'
    local glow=$'\033[38;2;191;151;255m'
    local dim=$'\033[2;38;2;115;93;148m'
    local -a symbols=('0' '1' 'x' 'F' '#' '/' '\' '_' '$' '*' '+' '?' '@' '%')
    local height=${#OBFARYN_ART[@]} width=0 line
    local row col progress delta char color oldcolor output frame last_frame
    local columns lines

    for line in "${OBFARYN_ART[@]}"; do
        ((${#line} > width)) && width=${#line}
    done

    # No animation when redirected, when colors are disabled, or in small terminals.
    if [[ ! -t 1 || ${TERM:-dumb} == dumb || -n ${NO_COLOR:-} || -n ${CI:-} ]]; then
        printf '%s\n' "${OBFARYN_ART[@]}"
        printf '\nObfaryn — JVM Bytecode Obfuscator\n'
        return
    fi

    columns=$(tput cols 2>/dev/null || printf '80')
    lines=$(tput lines 2>/dev/null || printf '24')
    if ((columns <= width || lines < height + 4)); then
        printf '%s\n' "${OBFARYN_ART[@]}"
        printf '\nObfaryn — JVM Bytecode Obfuscator\n'
        return
    fi

    # Draw the complete, readable logo first.
    printf '\033[?25l'
    for line in "${OBFARYN_ART[@]}"; do
        printf '%s%s%s\n' "$purple" "$line" "$reset"
    done
    sleep 0.45

    # Sweep from top-left to bottom-right.
    last_frame=$((width + (height - 1) * 3 + 12))
    for ((frame=-8; frame<=last_frame; frame+=2)); do
        printf '\033[%dA' "$height"
        for ((row=0; row<height; row++)); do
            line=${OBFARYN_ART[row]}
            output=''
            oldcolor=''
            for ((col=0; col<${#line}; col++)); do
                char=${line:col:1}
                progress=$((frame - col - row * 3))
                if [[ $char == ' ' ]]; then
                    color=$reset
                elif ((progress < -3)); then
                    color=$purple
                elif ((progress <= 2)); then
                    color=$cyan
                    char=${symbols[RANDOM % ${#symbols[@]}]}
                elif ((progress <= 8)); then
                    color=$glow
                    char=${symbols[RANDOM % ${#symbols[@]}]}
                else
                    color=$dim
                    # Stable scrambled final character for this position.
                    char=${symbols[(col * 7 + row * 11 + 3) % ${#symbols[@]}]}
                fi
                if [[ $color != "$oldcolor" ]]; then
                    output+="$color"
                    oldcolor=$color
                fi
                output+="$char"
            done
            printf '\r\033[2K%s%s\n' "$output" "$reset"
        done
        sleep 0.035
    done

    # Finish below the scrambled logo; never rewind the cursor again.
    printf '\033[?25h\n%sObfaryn%s — JVM Bytecode Obfuscator\n' "$purple" "$reset"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    obfaryn_banner
    printf 'Installing Obfaryn...\n'
fi
