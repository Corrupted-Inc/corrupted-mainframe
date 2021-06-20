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
In the same directory you run the bot from, make a file called `config.json`.
This configuration file will follow this format:
```json
{
  "token": "bot token here",
  "permaAdmins": ["your user ID here", "optional other user id here"],
  "databaseUrl": "jdbc:postgresql://db:5432/postgres?user=postgres&password=PASSWORD GOES HERE",
  "databaseDriver": "org.postgresql.Driver",
  "gitUrl": "https://github.com/Corrupted-Inc/corrupted-mainframe/"
}
```

## Running
1. Place your config file, corrupted-mainframe.jar (can be downloaded from releases or built locally), and docker-compose.yaml in the same directory
1. `DB_PASSWORD="the password from the config file" docker-compose up`

## Contributing
Pull requests are welcome!  If you find a security vulnerability, please contact an admin
directly.  They can be found in [our discord](https://discord.gg/WF8HU47PDc).
