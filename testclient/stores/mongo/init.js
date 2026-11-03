// shopdocs (MongoDB, localhost:27017, teo / kaima, authSource admin): reviews and an audit log, as documents
const shop = db.getSiblingDB('shopdocs');

shop.reviews.insertMany([
  { product: 'Coffee', stars: 5, by: 'ada@example.com', text: 'Strong and smooth', tags: ['hot', 'morning'] },
  { product: 'Coffee', stars: 4, by: 'grace@example.com', text: 'Good, a bit bitter', tags: ['hot'] },
  { product: 'Tea', stars: 3, by: 'alan@example.com', text: 'Fine', tags: ['hot', 'calm'] },
  { product: 'Cake', stars: 5, by: 'linus@example.com', text: 'Best cake in town', tags: ['sweet'],
    details: { slices: 2, extras: ['cream'] } },
]);

shop.audit.insertMany([
  { at: new Date(Date.now() - 3600e3), action: 'price.changed', product: 'Cake', from: 4.5, to: 4.8 },
  { at: new Date(Date.now() - 600e3), action: 'stock.low', product: 'Tea', onHand: 15 },
]);

shop.reviews.createIndex({ product: 1 });
