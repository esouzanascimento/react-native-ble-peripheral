#import <Foundation/Foundation.h>
#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

@interface RCT_EXTERN_MODULE(BLEPeripheral, RCTEventEmitter)

// Promise-based methods
RCT_EXTERN_METHOD(
  isAdvertising:
  (RCTPromiseResolveBlock)resolve
  rejecter: (RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(
  start:
  (RCTPromiseResolveBlock)resolve
  rejecter: (RCTPromiseRejectBlock)reject
)

// Other methods
RCT_EXTERN_METHOD(setName: (NSString *)name)

RCT_EXTERN_METHOD(
  addService: (NSString *)uuid
  primary: (BOOL)primary
)

RCT_EXTERN_METHOD(
  addCharacteristicToService: (NSString *)serviceUUID
  uuid: (NSString *)uuid
  permissions: (NSUInteger)permissions
  properties: (NSUInteger)properties
)

RCT_EXTERN_METHOD(
  addCharacteristicToServiceWithValue: (NSString *)serviceUUID
  uuid: (NSString *)uuid
  permissions: (NSUInteger)permissions
  properties: (NSUInteger)properties
  value: (NSString *)value
)

RCT_EXTERN_METHOD(stop)

RCT_EXTERN_METHOD(
  sendNotificationToDevices: (NSString *)serviceUUID
  characteristicUUID: (NSString *)characteristicUUID
  data: (NSData *)data
)

// Static method
+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

@end
