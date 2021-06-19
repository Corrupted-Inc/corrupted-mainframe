# Corrupted Mainframe
*A JDA discord bot*

This is a general-purpose discord bot.  Right now it has moderation features, 
music, and utilities.

## Building
1. Clone the repo<br>
 `git clone https://github.com/Corrupted-Inc/corrupted-mainframe.git`
1. CD into the directory
1. `./gradlew bot:jar` (linux/mac) or `gradlew.bat bot:jar` (windows)
1. The output jar will be located at bot/build/libs/corrupted-mainframe.jar

## Configuration
In the same directory you run the jar from, make a file called `config.json`.
This configuration file will follow this format:
```json
{
  "token": "bot token here",
  "permaAdmins": ["your user ID here", "optional other user id here"],
  "databaseUrl": "database URL here",
  "databaseDriver": "database driver here",
  "gitUrl": "https://github.com/Corrupted-Inc/corrupted-mainframe/"
}
```
If you don't know what the database URL and driver are, use the following:
``` <!--- no syntax highlighting because it is only part of a json file -->
  "databaseUrl": "jdbc:sqlite:database.db",
  "databaseDriver": "org.sqlite.JDBC"
```
You can create the SQLITE database like so:
`sqlite3 database.db "VACUUM;"`

## Contributing
Pull requests are welcome!  If you find a security vulnerability, please contact an admin
directly in [our discord](https://discord.gg/WF8HU47PDc).
