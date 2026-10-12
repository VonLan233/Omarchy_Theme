# Interactive Bash only; scripts and remote commands keep their normal output.
if [[ $- == *i* ]]; then
    PS1='\[\e[35m\]feiyu@oppo\[\e[0m\] \[\e[90m\]\w\[\e[0m\]\n\[\e[32m\]> \[\e[0m\]'
fi
