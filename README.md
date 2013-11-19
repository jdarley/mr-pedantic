# SHuppet - Puppet for AWS configuration

## Intro

Shuppet keeps AWS configuration in a consistent state.

An application deployed in AWS needs at least:
 * an elastic load balancer
 * a security group for the elastic load balancer
 * another security for the application
 * an IAM role to be able to access other AWS APIs

Shuppet creates all this configuration for you and ensures that it stays as you defined it by checking it and updating it when required.
On top of this Shuppet also supports:
 * S3
 * DynamoDb

Shuppet also creates a configuration per environment and ensures that it stays as defined.

All the configuration is stored in GIT repositories in source.nokia.com in the _shuppet_ project.

Shuppet uses Campfire to communicate configuration changes.

## Resources

`GET /1.x/healthcheck`

`GET /1.x/ping`
`pong`.

`GET /1.x/status`
Status information in JSON form.

`GET /1.x/icon`
The JPEG representation of Exploud.

`GET /1.x/envs`
All available environments

`GET /1.x/envs/:env-name`
* Read and evaluate the environment configuration _{:env-name}.clj_ from GIT repository _{:env-name}_, return the configuration in JSON.

`GET /1.x/envs/:env-name/apply`
Applies the environment configuration

`GET /1.x/envs/:env-name/apps`
All available applications

`GET /1.x/envs/:env-name/apps/app-name`
* Read the application configuration _{:app-name}.clj_ from GIT repository _{:app-name}_ and evaluate it with the environment configuration, return the configuration in JSON. Master branch is used for all environments except for production where prod branch is used instead.

`GET /1.x/envs/:env-name/apps/app-name/apply`
Applies the application configuration to the sepcified environment

`POST /1.x/apps/:app-name`
Creates a basic configuration

## Configuration file

Shuppet configuration files are in Clojure, the only requirement is to return a Clojure map.
The configuration uses same naming as in Amazon's APIs, so you can refer to http://aws.amazon.com/documentation/ for help.

Examples:
* environment configuration https://source.nokia.com/projects/6302-shuppet/repositories/29637/entry/master/poke.clj
* application configuration https://source.nokia.com/projects/6302-shuppet/repositories/29679/entry/master/test.clj