#!/bin/bash

# Make sure it's being run in the correct dir
if [ -d "bot" ]; then
    cd image || exit 1;
fi

# Copy the latest jar to the current dir
cp ../bot/build/libs/corrupted-mainframe.jar .;

# Build the image (automatically given the 'latest' tag)
docker build -t "corrupted-inc/corrupted-mainframe" .
