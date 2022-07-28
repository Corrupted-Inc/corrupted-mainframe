# Corrupted Mainframe
*A JDA discord bot written in the Kotlin programming language*

This is a general-purpose discord bot. Currently, it has moderation features, 
music, and various utilities.

## Building
1. Clone the repo<br>
 `git clone https://github.com/Corrupted-Inc/corrupted-mainframe.git`
1. CD into the directory
1. `./gradlew bot:jar` (linux/mac) or `gradlew.bat bot:jar` (windows)
   `bot:assemble` will make a sources jar
1. The output jar will be located at bot/build/libs/corrupted-mainframe.jar

## Configuration
In the same directory you run the bot from, make a file called `config.json`.
This configuration file will follow this format:
```json
{
  "token": "bot token here",
  "permaAdmins": ["your user ID here", "optional other user id here"],
  "gitUrl": "https://github.com/Corrupted-Inc/corrupted-mainframe/"
}
```

## Running
1. Place your config file, corrupted-mainframe.jar (can be downloaded from releases or built locally), and docker-compose.yaml in the same directory
1. `DB_PASSWORD="the password from the config file" docker-compose up`

## Contributing
Pull requests are welcome!  If you find a security vulnerability, please contact an admin
directly.  We can be found in [our discord](https://discord.gg/WF8HU47PDc).

## TODOs
 * New features
   * Update readme
   * Add botBuild to all DB tables
   * New calculator backend (ideally one that supports CAS or at least numeric equation solving)
   * Add common parent table for snowflakes?
   * General-purpose migrations system
   * Generalized commands/autocomplete interface
     * Define object, enumerate with reflection
     * Annotate parameters with name and description
       * Compile-time type checking?
     * Define subcommands as methods
   * Example plugin repository
   * Per-guild custom fight attacks
   * Change `table()` to generate SVGs and render them for actually good formatting
   * Moderation command log
   * Manager
     * Shard statistics
     * Restart shards
     * Deploy update
     * Profiling
     * Integrated with kubernetes
     * Centralized logging
     * Automated DB snapshots
   * General-purpose settings system
 * Bugfixes
    * Somehow catch `ClassNotFoundException`s due to modified JAR and restart the bot, or force load all classes at boot
 * Finish
   * Quotes
   * Repost detector
   * Fight records
 * Cleanup and refactoring
   * Remove unneeded tables and columns
   * Split up commands into yet more files
